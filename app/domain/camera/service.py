"""
카메라 서비스
- process_frame: landmark JSON 수신 → AI 추론 → Kotlin internal API로 realtime/fall-log 위임 (WebSocket)
- score: Redis에서 현재 위험 점수 조회
- status / url: Firestore devices 컬렉션 조회
"""
import logging
import uuid
from datetime import datetime, timezone

import httpx
from google.cloud.firestore_v1.base_query import FieldFilter
from app.ai.buffer import save_score, get_score, check_caution_cooldown, check_danger_cooldown, apply_score_floor
from app.ai.engine import infer_landmarks_async, classify_level, WARNING_THRESHOLD, CRITICAL_THRESHOLD
from app.core.config import settings
from app.core.exceptions import not_found
from app.core.firebase import get_firestore
from app.core.storage import upload_video
from app.domain.camera.schemas import (
    StreamResponse, ScoreResponse, StatusResponse,
    CameraUrlResponse,
)

logger = logging.getLogger(__name__)

_DEVICES = "devices"


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
    await _save_realtime_data(user_id, features, raw_score)  # 분석용 원본 score 보존 (sticky 미적용)
    await _update_realtime(user_id, score, level)

    log_id: str | None = None
    if score > CRITICAL_THRESHOLD or fall:
        if await check_danger_cooldown(user_id):
            log_id = await _save_fall_log(user_id, device_id, score, fall, video_bytes=None)
    elif score > WARNING_THRESHOLD:
        if await check_caution_cooldown(user_id):
            log_id = await _save_fall_log(user_id, device_id, score, fall, video_bytes=None)

    return StreamResponse(score=score, fall=fall, level=level, log_id=log_id)


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
                timeout=3.0,
            )
    except Exception as e:
        logger.error("/internal/fall-log 호출 실패 log_id=%s user_id=%s: %s", log_id, user_id, e)
    return log_id


async def get_score_for_user(user_id: str) -> ScoreResponse:
    data = await get_score(user_id)
    score = data["score"] if data else 0.0
    level = data["level"] if data else "정상"
    return ScoreResponse(user_id=user_id, score=score, level=level)


async def get_device_status(device_id: str) -> StatusResponse:
    db = get_firestore()
    doc = await db.collection(_DEVICES).document(device_id).get()
    if not doc.exists:
        raise not_found("기기를 찾을 수 없습니다")
    d = doc.to_dict()
    return StatusResponse(
        device_id=device_id,
        status=d.get("status", "offline"),
        last_seen=str(d.get("last_seen", "")),
    )


async def get_camera_url(device_id: str) -> CameraUrlResponse:
    db = get_firestore()
    doc = await db.collection(_DEVICES).document(device_id).get()
    if not doc.exists:
        raise not_found("기기를 찾을 수 없습니다")
    d = doc.to_dict()
    return CameraUrlResponse(device_id=device_id, camera_url=d.get("camera_url"))


