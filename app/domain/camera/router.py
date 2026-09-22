import logging
import time

from fastapi import APIRouter, Query, WebSocket, WebSocketDisconnect
from jose import JWTError
from redis.exceptions import RedisError
from app.core.security import decode_access_token, is_token_rejected
from app.domain.camera import service

logger = logging.getLogger(__name__)

# 카메라 REST(score·status·url)는 앱이 호출하지 않고 Kotlin /api/camera/*와 경로가 겹쳐 삭제했다(완료 문서 D8).
ws_router = APIRouter(tags=["Camera WebSocket"])

# 연결 뒤에도 주기적으로 토큰 상태를 다시 본다(C8). 연결 시점 1회 검증만 하면 로그아웃·비밀번호 변경·
# 탈퇴가 일어나도 촬영이 끝날 때까지(상시 기기면 며칠) 스트리밍이 이어지고 그동안 추론 결과가 계속 쌓인다.
# 연결당 5분에 Redis MGET 1회 — 상시 카메라 1,000대에서 약 3.3 req/s.
# 만료(exp)는 여기서 보지 않는다: access 수명이 1시간이라 끊으면 1시간마다 감시가 멈추는데,
# 앱에는 자동 재연결이 없어 사람이 다시 시작해야 한다. "보안상 끊어야 하는 것"만 끊는다.
RECHECK_INTERVAL_SECONDS = 300


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
    last_checked = time.monotonic()

    try:
        while True:
            data = await websocket.receive_json()

            # 프레임마다 확인하면 Redis 왕복이 초당 수십 회가 되므로 시간 기준으로만 확인한다.
            if time.monotonic() - last_checked >= RECHECK_INTERVAL_SECONDS:
                last_checked = time.monotonic()
                try:
                    if await is_token_rejected(token, payload):
                        logger.info("WS 세션 무효화로 연결 종료: user_id=%s", user_id)
                        await websocket.close(code=1008)
                        return
                except RedisError as e:
                    # 연결 시점과 달리 여기서는 끊지 않는다 — 이미 인증된 스트리밍을 Redis 순단으로
                    # 끊으면 앱에 자동 재연결이 없어 낙상 감지가 멈춘다. 다음 주기에 다시 확인한다.
                    # Redis 장애 중에는 무효화 쓰기도 실패하므로 놓치는 무효화도 사실상 없다.
                    logger.warning("WS 토큰 재확인 실패(스트리밍 유지): %s", e)

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
