import logging

from fastapi import APIRouter, Query, WebSocket, WebSocketDisconnect
from jose import JWTError
from redis.exceptions import RedisError
from app.core.security import decode_access_token, is_token_rejected
from app.domain.camera import service

logger = logging.getLogger(__name__)

# 카메라 REST(score·status·url)는 앱이 호출하지 않고 Kotlin /api/camera/*와 경로가 겹쳐 삭제했다(완료 문서 D8).
ws_router = APIRouter(tags=["Camera WebSocket"])


@ws_router.websocket("/ws/stream")
async def ws_stream(websocket: WebSocket, token: str = Query(...)):
    try:
        payload = decode_access_token(token)
    except JWTError:
        await websocket.close(code=1008)
        return
    try:
        rejected = await is_token_rejected(token, payload)
    except RedisError as e:
        logger.error("WS 토큰 블랙리스트·무효화 조회 실패: %s", e)
        await websocket.close(code=1011)
        return
    if rejected:  # 로그아웃했거나 탈퇴·비밀번호 변경으로 끊긴 세션
        await websocket.close(code=1008)
        return

    await websocket.accept()

    # 추론 결과(낙상 기록·보호자 알림)의 명의는 토큰 사용자로 고정한다. init 메시지의 user_id를 믿으면
    # 로그인한 누구나 남의 명의로 낙상 기록을 만들 수 있다. 앱은 여전히 user_id를 보내지만 무시한다.
    user_id: str = payload["sub"]
    device_id: str | None = None

    try:
        while True:
            data = await websocket.receive_json()
            msg_type = data.get("type")

            if msg_type == "init":
                device_id = data.get("device_id")
                await websocket.send_json({"type": "init_ok"})

            elif msg_type == "frame":
                if not device_id:
                    await websocket.send_json({"type": "error", "message": "init 먼저 전송 필요"})
                    continue
                landmarks = data.get("landmarks", [])
                timestamp = data.get("timestamp", 0.0)
                result = await service.process_frame(landmarks, timestamp, user_id, device_id)
                await websocket.send_json({
                    "type": "result",
                    "fall_score": result.score,
                    "fall": result.fall,
                    "level": result.level,
                    "log_id": result.log_id,
                })

    except WebSocketDisconnect:
        pass
