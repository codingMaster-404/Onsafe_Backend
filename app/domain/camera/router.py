import logging
import time

from fastapi import APIRouter, Query, WebSocket, WebSocketDisconnect
from jose import ExpiredSignatureError, JWTError
from redis.exceptions import RedisError
from app.ai.buffer import mark_stream_alive
from app.ai.engine import claim_device, release_device
from app.core.log_throttle import LogThrottle
from app.core.security import decode_access_token, is_token_rejected
from app.domain.camera import service

logger = logging.getLogger(__name__)

# 카메라 REST(score·status·url)는 앱이 호출하지 않고 Kotlin /api/camera/*와 경로가 겹쳐 삭제했다(완료 문서 D8).
ws_router = APIRouter(tags=["Camera WebSocket"])

# 연결 뒤에도 주기적으로 토큰 상태를 다시 본다(C8). 연결 시점 1회 검증만 하면 로그아웃·비밀번호 변경·
# 탈퇴가 일어나도 촬영이 끝날 때까지(상시 기기면 며칠) 스트리밍이 이어지고 그동안 추론 결과가 계속 쌓인다.
# 연결당 5분에 Redis MGET 1회 — 상시 카메라 1,000대에서 약 3.3 req/s.
# 만료(exp)는 여기서 보지 않는다: access 수명이 1시간이라 끊으면 그때마다 재연결 공백(refresh·init·
# 윈도우 30프레임)이 생긴다. "보안상 끊어야 하는 것"만 끊는다. 연결은 Cloud Run 요청 시간 제한(1시간)으로
# 끊기고, 그때 앱이 refresh 후 재연결한다.
RECHECK_INTERVAL_SECONDS = 300

# 종료 코드 — 앱이 끊긴 이유에 따라 다르게 대응한다(카메라 스트리밍 B2, 4000번대는 애플리케이션용).
CLOSE_TOKEN_EXPIRED = 4001  # access 만료 → refresh 후 재연결
CLOSE_UNAUTHORIZED = 4003   # 토큰 없음·위조·access 아님·로그아웃·세션 무효화 → 로그인 필요, 재연결 금지
CLOSE_SERVER_ERROR = 1011   # 토큰 상태를 확인할 수 없음(Redis 장애) → 잠시 후 재연결

# 프레임 수신 생존 신호(B5) 갱신 간격 — 키 TTL(90초) 안에 여러 번 갱신되도록 짧게, 프레임마다는 아니게.
STREAM_ALIVE_REFRESH_SECONDS = 30


@ws_router.websocket("/ws/stream")
async def ws_stream(websocket: WebSocket, token: str | None = Query(None)):
    # 인증 전에 먼저 수락한다. accept 전에 close하면 서버가 HTTP 403으로 핸드셰이크를 거절해
    # 앱은 종료 코드를 받지 못하고 "연결 실패"만 알게 된다. 수락 후 프레임은 하나도 처리하지 않고 닫는다.
    await websocket.accept()

    if not token:
        await websocket.close(code=CLOSE_UNAUTHORIZED)
        return
    try:
        payload = decode_access_token(token)
    except ExpiredSignatureError:
        await websocket.close(code=CLOSE_TOKEN_EXPIRED)
        return
    except JWTError:
        await websocket.close(code=CLOSE_UNAUTHORIZED)
        return
    try:
        rejected = await is_token_rejected(token, payload)
    except RedisError as e:
        logger.error("WS 토큰 블랙리스트·무효화 조회 실패: %s", e)
        await websocket.close(code=CLOSE_SERVER_ERROR)
        return
    if rejected:  # 로그아웃했거나 탈퇴·비밀번호 변경으로 끊긴 세션
        await websocket.close(code=CLOSE_UNAUTHORIZED)
        return

    # 추론 결과(낙상 기록·보호자 알림)의 명의는 토큰 사용자로 고정한다. init 메시지의 user_id를 믿으면
    # 로그인한 누구나 남의 명의로 낙상 기록을 만들 수 있다. 앱은 여전히 user_id를 보내지만 무시한다.
    user_id: str = payload["sub"]
    device_id: str | None = None
    device_owner: object | None = None  # engine.claim_device가 준 이 연결의 기기 소유 토큰
    last_checked = time.monotonic()
    last_alive_marked: float | None = None  # 첫 프레임에서 바로 생존 신호를 남긴다

    # 이 연결의 메시지 처리 실패 로그 — 같은 원인이면 프레임마다(초당 최대 30회) 실패하므로 60초에 1번만 남긴다.
    message_error_log = LogThrottle()

    try:
        while True:
            # 수신 자체의 실패(JSON 아님·바이너리)는 잡지 않고 연결을 끊는다 — 소켓이 깨진 상태에서
            # 계속 받으면 같은 실패를 끝없이 반복할 수 있다. 앱은 항상 JSON 객체를 보낸다.
            data = await websocket.receive_json()

            # 메시지 1건 처리가 실패해도 연결은 유지한다(D7). 끊으면 앱이 재연결하면서 윈도우(30프레임)를
            # 처음부터 다시 쌓아 그동안 감지가 멈춘다. 연결 종료(WebSocketDisconnect)만 그대로 올린다.
            try:
                if not isinstance(data, dict):
                    await websocket.send_json({"type": "error", "message": "잘못된 메시지 형식"})
                    continue

                # 프레임마다 확인하면 Redis 왕복이 초당 수십 회가 되므로 시간 기준으로만 확인한다.
                if time.monotonic() - last_checked >= RECHECK_INTERVAL_SECONDS:
                    last_checked = time.monotonic()
                    try:
                        if await is_token_rejected(token, payload):
                            logger.info("WS 세션 무효화로 연결 종료: user_id=%s", user_id)
                            await websocket.close(code=CLOSE_UNAUTHORIZED)
                            return
                    except RedisError as e:
                        # 연결 시점과 달리 여기서는 끊지 않는다 — 이미 인증된 스트리밍을 Redis 순단으로
                        # 끊으면 재연결도 같은 장애(1011)로 실패해 낙상 감지가 멈춘다. 다음 주기에 다시 확인한다.
                        # Redis 장애 중에는 무효화 쓰기도 실패하므로 놓치는 무효화도 사실상 없다.
                        logger.warning("WS 토큰 재확인 실패(스트리밍 유지): %s", e)

                msg_type = data.get("type")

                if msg_type == "init":
                    # 재연결·재init마다 기기 버퍼를 비운다(B3) — 앱은 연결마다 timestamp를 0부터 센다.
                    # 같은 연결에서 다른 기기로 다시 init하면 이전 기기 상태는 여기서 정리한다(B4).
                    if device_id and device_owner is not None:
                        release_device(device_id, device_owner)
                    device_id, device_owner = None, None
                    # 문자열이 아닌 device_id는 없는 것과 같게 본다 — 그대로 두면 기기 상태 dict의 키로 쓰여
                    # 이후 프레임이 모두 실패한다. 응답은 지금처럼 init_ok, 프레임은 "init 먼저"로 거절된다.
                    requested_device_id = data.get("device_id")
                    if isinstance(requested_device_id, str) and requested_device_id:
                        device_owner = claim_device(requested_device_id)
                        device_id = requested_device_id
                    await websocket.send_json({"type": "init_ok"})

                elif msg_type == "frame":
                    if not device_id:
                        await websocket.send_json({"type": "error", "message": "init 먼저 전송 필요"})
                        continue
                    landmarks = data.get("landmarks", [])
                    timestamp = data.get("timestamp", 0.0)
                    result = await service.process_frame(landmarks, timestamp, user_id, device_id)
                    # 프레임이 실제로 처리되고 있다는 신호(B5). heartbeat는 WS와 무관하게 오므로 워치독이
                    # 이 키로 "앱은 켜져 있는데 감지가 멈춤"을 가린다. 처리에 성공한 뒤에만 남겨, 프레임은
                    # 들어오는데 처리가 계속 실패하는 경우도 감지 중단으로 잡히게 한다. 실패해도 스트리밍은 계속한다.
                    if last_alive_marked is None or time.monotonic() - last_alive_marked >= STREAM_ALIVE_REFRESH_SECONDS:
                        last_alive_marked = time.monotonic()
                        try:
                            await mark_stream_alive(user_id)
                        except RedisError as e:
                            logger.warning("스트리밍 생존 신호 기록 실패(스트리밍 유지): %s", e)
                    await websocket.send_json({
                        "type": "result",
                        "fall_score": result.score,
                        "fall": result.fall,
                        "level": result.level,
                        "log_id": result.log_id,
                    })
            except WebSocketDisconnect:
                raise
            except Exception:
                message_error_log.log(
                    logger, logging.ERROR, "message",
                    "WS 메시지 처리 실패(연결 유지) user_id=%s device_id=%s", user_id, device_id, exc_info=True,
                )
                # 예외 내용은 앱에 보내지 않는다 — 원인은 서버 로그로 본다. 앱은 모르는 type을 무시한다.
                await websocket.send_json({"type": "error", "message": "프레임 처리 실패"})

    except WebSocketDisconnect:
        pass
    finally:
        # 끊긴 기기 상태를 남겨 두지 않는다(B4). 다른 연결이 같은 기기로 이미 init했으면 건드리지 않는다.
        if device_id and device_owner is not None:
            release_device(device_id, device_owner)
