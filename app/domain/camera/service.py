"""
카메라 서비스
- process_frame: landmark JSON 수신 → AI 추론 → Kotlin internal API로 realtime/fall-log 위임 (WebSocket)
  (점수·기기 상태 조회 REST는 Kotlin /api/camera/*가 담당 — Python 쪽은 삭제, 완료 문서 D8)
"""
import logging
import time
import uuid
from datetime import datetime, timezone

import httpx
from google.cloud.firestore_v1.base_query import FieldFilter
from redis.exceptions import RedisError
from app.ai.buffer import (
    save_score, get_score, apply_score_floor,
    check_caution_cooldown, check_danger_cooldown, shorten_caution_cooldown, shorten_danger_cooldown,
    CAUTION_COOLDOWN_SECONDS, DANGER_COOLDOWN_SECONDS, SAVE_RETRY_SECONDS,
)
from app.ai.engine import infer_landmarks_async, classify_level, WARNING_THRESHOLD, CRITICAL_THRESHOLD
from app.core.config import settings
from app.core.firebase import get_firestore
from app.core.storage import upload_video
from app.domain.camera.schemas import StreamResponse

logger = logging.getLogger(__name__)

# Kotlin /internal/* 는 JWT 필터를 타지 않고 공개 노출돼 있어 이 헤더로만 호출자를 구분한다.
# 값이 비면 Kotlin이 403으로 거부한다(fail-closed) — 배포 시 두 서비스에 같은 시크릿을 넣어야 한다.
_INTERNAL_HEADERS = {"X-Internal-Auth": settings.internal_job_secret}

# Kotlin 콜드 스타트(Spring + Firebase 초기화)가 3초를 넘을 수 있어 낙상 저장만 10초를 준다.
# 실패 판정이 늦어지는 대신 첫 이벤트를 놓치지 않는다. /internal/realtime은 3초 유지.
_FALL_LOG_TIMEOUT_SECONDS = 10.0

_REALTIME = "realtime_data"
_REALTIME_LIMIT = 2000

# 등급별 (쿨다운 확인, 실패 시 재시도 간격으로 줄이기, 쿨다운 기간)
_COOLDOWNS = {
    "danger": (check_danger_cooldown, shorten_danger_cooldown, DANGER_COOLDOWN_SECONDS),
    "caution": (check_caution_cooldown, shorten_caution_cooldown, CAUTION_COOLDOWN_SECONDS),
}

# Redis를 쓸 수 없을 때만 쓰는 프로세스 메모리 쿨다운 — {(등급, user_id): 만료 시각(monotonic)}.
# 쿨다운 확인이 실패해도 저장은 시도하되(fail-open), 위험 점수가 이어지는 동안 추론마다(초당 약 6회)
# 보호자 알림이 나가지 않게 인스턴스 안에서라도 막는다. 인스턴스가 여러 개면 그 수만큼 알림이 갈 수 있다.
_local_cooldowns: dict[tuple[str, str], float] = {}


async def process_frame(landmarks: list, timestamp: float, user_id: str, device_id: str) -> StreamResponse:
    result = await infer_landmarks_async(landmarks, device_id, timestamp)

    # 추론 실패(전처리·추론 예외)를 정상 스킵과 구분한다.
    # 실패를 "정상"으로 단정하면 포즈 검출이 나쁜 순간의 낙상을 안전으로 오보한다.
    # → 이 윈도우는 알림 판정에서 제외하고, 보호자에게는 직전 캐시 점수를 유지해 보여준다.
    if result.get("status") == "error":
        try:
            cached = await get_score(user_id)
        except RedisError as e:
            logger.warning("직전 점수 조회 실패 user_id=%s: %s", user_id, e)
            cached = None
        if cached:
            return StreamResponse(score=cached["score"], fall=False, level=cached["level"])
        return StreamResponse(score=0.0, fall=False, level=None)

    if not result["features"]:  # 윈도우 미달 또는 STRIDE 미달 (정상 스킵)
        return StreamResponse(score=0.0, fall=False, level="정상")
    raw_score: float = result["score"]
    fall: bool = result["fall"]
    features: dict = result["features"]

    # score>75 진입 시 15분간 하락 방지(더 높은 값으로는 갱신 허용) — 스펙 확정, 2026-07-13
    # Redis 장애로 여기서 멈추면 아래 알림 판정까지 가지 못한다 — 하한 없이 원래 점수로 판정한다.
    try:
        score = await apply_score_floor(user_id, raw_score, CRITICAL_THRESHOLD)
    except RedisError as e:
        logger.warning("점수 하한 적용 실패 — 원래 점수로 판정 user_id=%s: %s", user_id, e)
        score = raw_score
    level = classify_level(score)

    try:
        await save_score(user_id, score, level)
    except RedisError as e:
        logger.warning("점수 캐시 저장 실패 user_id=%s: %s", user_id, e)
    # [중단 2026-09-16] 분석용 features 적재 — 읽는 코드가 어디에도 없어 중단했다(§4 C11).
    #   · 앱·Kotlin·AI 추론 어느 쪽도 이 데이터를 조회하지 않는다(전수 검색 확인).
    #   · 사용자당 최대 2000문서 × 50필드가 쌓이는데 관절 각도 시계열은 건강 관련 민감정보에 가깝다.
    #   · Kotlin realtime_data/{userId}(현재 점수·상태)와 같은 컬렉션에 자동 ID로 섞여 있어
    #     탈퇴 캐스케이드(문서 ID = userId 단건 삭제)가 이 문서들을 지우지 못한다.
    # 재학습 등으로 다시 필요하면 아래 한 줄을 되살리면 된다. 단 그때는 별도 컬렉션
    # (예: realtime_features)로 분리하고 탈퇴 삭제 대상에 함께 넣어야 한다.
    # await _save_realtime_data(user_id, features, raw_score)  # 분석용 원본 score 보존 (sticky 미적용)
    await _update_realtime(user_id, score, level)

    log_id: str | None = None
    if score > CRITICAL_THRESHOLD or fall:
        log_id = await _save_event_with_cooldown("danger", user_id, device_id, score, fall)
    elif score > WARNING_THRESHOLD:
        log_id = await _save_event_with_cooldown("caution", user_id, device_id, score, fall)

    return StreamResponse(score=score, fall=fall, level=level, log_id=log_id)


async def _save_event_with_cooldown(kind: str, user_id: str, device_id: str, score: float, fall: bool) -> str | None:
    """쿨다운을 잡은 경우에만 이벤트를 저장한다. 저장이 실패하면 쿨다운을 SAVE_RETRY_SECONDS로 줄여
    그 뒤 추론이 다시 저장하게 한다 — 실패한 이벤트가 쿨다운 기간(최대 6시간) 동안 묻히지 않도록.
    Redis 오류 시에는 메모리 쿨다운으로 판정한다(중복 알림이 누락보다 낫다)."""
    check, shorten, ttl_seconds = _COOLDOWNS[kind]
    try:
        acquired = await check(user_id)
        redis_ok = True
    except RedisError as e:
        logger.warning("%s 쿨다운 확인 실패 — 메모리 쿨다운으로 판정 user_id=%s: %s", kind, user_id, e)
        acquired = _acquire_local_cooldown(kind, user_id, ttl_seconds)
        redis_ok = False
    if not acquired:
        return None

    log_id = await _save_fall_log(user_id, device_id, score, fall, video_bytes=None)
    if log_id is None:
        if redis_ok:
            try:
                await shorten(user_id)
            except RedisError as e:
                logger.warning("%s 쿨다운 단축 실패 — 원래 기간 동안 재시도 안 됨 user_id=%s: %s", kind, user_id, e)
        else:
            _local_cooldowns[(kind, user_id)] = time.monotonic() + SAVE_RETRY_SECONDS
    return log_id


def _acquire_local_cooldown(kind: str, user_id: str, ttl_seconds: float) -> bool:
    now = time.monotonic()
    for key in [k for k, expires_at in _local_cooldowns.items() if expires_at <= now]:
        del _local_cooldowns[key]
    if (kind, user_id) in _local_cooldowns:
        return False
    _local_cooldowns[(kind, user_id)] = now + ttl_seconds
    return True


# [현재 미사용] process_frame에서 호출을 주석 처리했다(위 [중단 2026-09-16] 참고).
# 되살릴 때를 위해 구현은 그대로 둔다 — 컬렉션 상수(_REALTIME)·2000개 트림 로직 포함.
async def _save_realtime_data(user_id: str, features: dict, score: float) -> None:
    if not features:
        return
    db = get_firestore()
    allowed = [
        'neck_angle', 'neck_angular_velocity', 'neck_angular_acceleration',
        'shoulder_balance_angle', 'shoulder_balance_angular_velocity', 'shoulder_balance_angular_acceleration',
        'shoulder_left_angle', 'shoulder_left_angular_velocity', 'shoulder_left_angular_acceleration',
        'shoulder_right_angle', 'shoulder_right_angular_velocity', 'shoulder_right_angular_acceleration',
        'elbow_left_angle', 'elbow_left_angular_velocity', 'elbow_left_angular_acceleration',
        'elbow_right_angle', 'elbow_right_angular_velocity', 'elbow_right_angular_acceleration',
        'hip_left_angle', 'hip_left_angular_velocity', 'hip_left_angular_acceleration',
        'hip_right_angle', 'hip_right_angular_velocity', 'hip_right_angular_acceleration',
        'knee_left_angle', 'knee_left_angular_velocity', 'knee_left_angular_acceleration',
        'knee_right_angle', 'knee_right_angular_velocity', 'knee_right_angular_acceleration',
        'ankle_left_angle', 'ankle_left_angular_velocity', 'ankle_left_angular_acceleration',
        'ankle_right_angle', 'ankle_right_angular_velocity', 'ankle_right_angular_acceleration',
        'torso_left_angle', 'torso_left_angular_velocity', 'torso_left_angular_acceleration',
        'torso_right_angle', 'torso_right_angular_velocity', 'torso_right_angular_acceleration',
        'spine_angle', 'spine_angular_velocity', 'spine_angular_acceleration',
        'center_distance', 'center_speed',
    ]
    data = {k: float(v) for k, v in features.items() if k in allowed}
    data["user_id"] = user_id
    data["risk_score"] = score
    data["timestamp"] = datetime.now(timezone.utc)

    col = db.collection(_REALTIME)
    await col.add(data)

    # 최신 2000개 유지 — 복합 인덱스(user_id ASC, timestamp DESC) 필요
    try:
        old_docs = await col.where(filter=FieldFilter("user_id", "==", user_id)) \
            .order_by("timestamp", direction="DESCENDING") \
            .offset(_REALTIME_LIMIT).get()
        for doc in old_docs:
            await doc.reference.delete()
    except Exception as e:
        logger.warning("realtime_data 정리 실패 user_id=%s (Firestore 복합 인덱스 확인 필요): %s", user_id, e)



async def _update_realtime(user_id: str, score: float, level: str) -> None:
    try:
        async with httpx.AsyncClient() as client:
            await client.post(
                f"{settings.kotlin_internal_base}/internal/realtime",
                json={"user_id": user_id, "score": score, "level": level},
                headers=_INTERNAL_HEADERS,
                timeout=3.0,
            )
    except Exception as e:
        logger.error("/internal/realtime 호출 실패 user_id=%s: %s", user_id, e)


async def _save_fall_log(user_id: str, device_id: str, score: float, fall: bool, video_bytes: bytes | None) -> str | None:
    """Kotlin /internal/fall-log에 저장을 위임한다.
    2xx 응답일 때만 log_id를 돌려주고, 4xx·5xx·타임아웃·연결 실패는 None — 호출부가 쿨다운을 풀어
    다음 추론이 다시 저장하게 하고, 앱이 없는 로그로 영상 업로드를 시도하지 않게 한다."""
    log_id = str(uuid.uuid4())

    video_url: str | None = None
    if video_bytes is not None:
        try:
            video_url = await upload_video(log_id, video_bytes)
        except Exception as e:
            logger.warning("동영상 업로드 실패 log_id=%s (fall-log는 계속 저장): %s", log_id, e)

    try:
        async with httpx.AsyncClient() as client:
            resp = await client.post(
                f"{settings.kotlin_internal_base}/internal/fall-log",
                json={
                    "log_id": log_id,
                    "device_id": device_id,
                    "user_id": user_id,
                    "score": score,
                    "fall": fall,
                    "is_confirmed": False,
                    "video_url": video_url,  # GCS 경로 or 에뮬레이터 URL
                },
                headers=_INTERNAL_HEADERS,
                timeout=_FALL_LOG_TIMEOUT_SECONDS,
            )
    except Exception as e:
        logger.error("/internal/fall-log 호출 실패 log_id=%s user_id=%s: %s", log_id, user_id, e)
        return None
    # httpx는 4xx·5xx에 예외를 내지 않는다 — 403(시크릿 불일치)·5xx도 여기서 실패로 본다
    if not resp.is_success:
        logger.error(
            "/internal/fall-log 저장 실패 status=%s log_id=%s user_id=%s body=%s",
            resp.status_code, log_id, user_id, resp.text[:200],
        )
        return None
    return log_id


