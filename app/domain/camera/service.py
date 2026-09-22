"""
카메라 서비스
- process_frame: landmark JSON 수신 → AI 추론 → Kotlin internal API로 realtime/fall-log 위임 (WebSocket)
  (점수·기기 상태 조회 REST는 Kotlin /api/camera/*가 담당 — Python 쪽은 삭제, 완료 문서 D8)
"""
import logging
import uuid
from datetime import datetime, timezone

import httpx
from google.cloud.firestore_v1.base_query import FieldFilter
from app.ai.buffer import save_score, get_score, check_caution_cooldown, check_danger_cooldown, apply_score_floor
from app.ai.engine import infer_landmarks_async, classify_level, WARNING_THRESHOLD, CRITICAL_THRESHOLD
from app.core.config import settings
from app.core.firebase import get_firestore
from app.core.storage import upload_video
from app.domain.camera.schemas import StreamResponse

logger = logging.getLogger(__name__)

# Kotlin /internal/* 는 JWT 필터를 타지 않고 공개 노출돼 있어 이 헤더로만 호출자를 구분한다.
# 값이 비면 Kotlin이 403으로 거부한다(fail-closed) — 배포 시 두 서비스에 같은 시크릿을 넣어야 한다.
_INTERNAL_HEADERS = {"X-Internal-Auth": settings.internal_job_secret}

_REALTIME = "realtime_data"
_REALTIME_LIMIT = 2000


async def process_frame(landmarks: list, timestamp: float, user_id: str, device_id: str) -> StreamResponse:
    result = await infer_landmarks_async(landmarks, device_id, timestamp)

    # 추론 실패(전처리·추론 예외)를 정상 스킵과 구분한다.
    # 실패를 "정상"으로 단정하면 포즈 검출이 나쁜 순간의 낙상을 안전으로 오보한다.
    # → 이 윈도우는 알림 판정에서 제외하고, 보호자에게는 직전 캐시 점수를 유지해 보여준다.
    if result.get("status") == "error":
        cached = await get_score(user_id)
        if cached:
            return StreamResponse(score=cached["score"], fall=False, level=cached["level"])
        return StreamResponse(score=0.0, fall=False, level=None)

    if not result["features"]:  # 윈도우 미달 또는 STRIDE 미달 (정상 스킵)
        return StreamResponse(score=0.0, fall=False, level="정상")
    raw_score: float = result["score"]
    fall: bool = result["fall"]
    features: dict = result["features"]

    # score>75 진입 시 15분간 하락 방지(더 높은 값으로는 갱신 허용) — 스펙 확정, 2026-07-13
    score = await apply_score_floor(user_id, raw_score, CRITICAL_THRESHOLD)
    level = classify_level(score)

    await save_score(user_id, score, level)
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
        if await check_danger_cooldown(user_id):
            log_id = await _save_fall_log(user_id, device_id, score, fall, video_bytes=None)
    elif score > WARNING_THRESHOLD:
        if await check_caution_cooldown(user_id):
            log_id = await _save_fall_log(user_id, device_id, score, fall, video_bytes=None)

    return StreamResponse(score=score, fall=fall, level=level, log_id=log_id)


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


async def _save_fall_log(user_id: str, device_id: str, score: float, fall: bool, video_bytes: bytes | None) -> str:
    log_id = str(uuid.uuid4())

    video_url: str | None = None
    if video_bytes is not None:
        try:
            video_url = await upload_video(log_id, video_bytes)
        except Exception as e:
            logger.warning("동영상 업로드 실패 log_id=%s (fall-log는 계속 저장): %s", log_id, e)

    try:
        async with httpx.AsyncClient() as client:
            await client.post(
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
                timeout=3.0,
            )
    except Exception as e:
        logger.error("/internal/fall-log 호출 실패 log_id=%s user_id=%s: %s", log_id, user_id, e)
    return log_id


