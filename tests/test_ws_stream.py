import logging

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from app.ai import engine
from app.domain.camera import router, service
from app.domain.camera.schemas import StreamResponse

DEVICE = "d1"
FRAME = {"type": "frame", "landmarks": [], "timestamp": 0.0}


@pytest.fixture
def ws(monkeypatch):
    """ws_router만 붙인 앱. 토큰 검증·생존 신호·process_frame을 대신한다.
    ws.fail로 process_frame이 던질 예외를, ws.alive_marks로 생존 신호 기록 횟수를 본다."""

    class Stream:
        def __init__(self):
            self.fail: Exception | None = None
            self.alive_marks = 0
            self.frames = 0

    s = Stream()

    async def not_rejected(token, payload):
        return False

    async def mark_stream_alive(user_id):
        s.alive_marks += 1

    async def process_frame(landmarks, timestamp, user_id, device_id):
        s.frames += 1
        if s.fail is not None:
            raise s.fail
        return StreamResponse(score=10.0, fall=False, level="정상")

    monkeypatch.setattr(router, "decode_access_token", lambda token: {"sub": "u1"})
    monkeypatch.setattr(router, "is_token_rejected", not_rejected)
    monkeypatch.setattr(router, "mark_stream_alive", mark_stream_alive)
    monkeypatch.setattr(service, "process_frame", process_frame)

    app = FastAPI()
    app.include_router(router.ws_router)
    s.client = TestClient(app)
    yield s
    engine._drop_device_state(DEVICE)
    engine._device_owners.pop(DEVICE, None)


def connect(ws):
    return ws.client.websocket_connect("/ws/stream?token=t")


def init(socket, device_id=DEVICE):
    socket.send_json({"type": "init", "device_id": device_id})
    assert socket.receive_json() == {"type": "init_ok"}


# ── 메시지 처리 실패 시 연결 유지 (D7·S11·S13) ─────────────────

def test_frame_failure_sends_generic_error_and_keeps_connection(ws):
    with connect(ws) as socket:
        init(socket)
        ws.fail = ValueError("secret detail")

        socket.send_json(FRAME)
        error = socket.receive_json()

        ws.fail = None
        socket.send_json(FRAME)
        result = socket.receive_json()

    assert error == {"type": "error", "message": "프레임 처리 실패"}
    assert result["type"] == "result"
    assert ws.frames == 2


def test_non_object_message_is_rejected_without_closing(ws):
    with connect(ws) as socket:
        socket.send_json(["init"])
        error = socket.receive_json()
        socket.send_json({"type": "init", "device_id": DEVICE})

        assert error == {"type": "error", "message": "잘못된 메시지 형식"}
        assert socket.receive_json() == {"type": "init_ok"}


@pytest.mark.parametrize("device_id", [{"id": "d1"}, ["d1"], 123, ""], ids=["dict", "list", "int", "empty"])
def test_non_string_device_id_is_treated_as_missing(ws, device_id):
    with connect(ws) as socket:
        init(socket, device_id=device_id)

        socket.send_json(FRAME)
        error = socket.receive_json()

    assert error == {"type": "error", "message": "init 먼저 전송 필요"}
    assert ws.frames == 0


def test_reinit_with_invalid_device_id_releases_previous_device(ws):
    with connect(ws) as socket:
        init(socket)
        assert DEVICE in engine._device_owners

        socket.send_json({"type": "init", "device_id": 123})
        socket.receive_json()
        socket.send_json(FRAME)
        error = socket.receive_json()

    assert DEVICE not in engine._device_owners
    assert error["message"] == "init 먼저 전송 필요"


def test_device_released_on_disconnect_after_failures(ws):
    with connect(ws) as socket:
        init(socket)
        ws.fail = ValueError("bug")
        socket.send_json(FRAME)
        socket.receive_json()

    assert DEVICE not in engine._device_owners


# ── 로그 (S14) ───────────────────────────────────────────────

def test_frame_failure_logged_once_per_interval_with_traceback(ws, caplog):
    caplog.set_level(logging.ERROR, logger=router.__name__)
    with connect(ws) as socket:
        init(socket)
        ws.fail = ValueError("bug")

        for _ in range(3):
            socket.send_json(FRAME)
            socket.receive_json()

    logged = [r for r in caplog.records if "WS 메시지 처리 실패" in r.getMessage()]
    assert len(logged) == 1
    assert logged[0].exc_info[0] is ValueError


# ── 생존 신호 (S15) ───────────────────────────────────────────

def test_stream_alive_not_marked_while_processing_fails(ws):
    with connect(ws) as socket:
        init(socket)
        ws.fail = ValueError("bug")

        for _ in range(3):
            socket.send_json(FRAME)
            socket.receive_json()

        assert ws.alive_marks == 0

        ws.fail = None
        socket.send_json(FRAME)
        socket.receive_json()

    assert ws.alive_marks == 1


def test_stream_alive_refreshed_by_interval_not_every_frame(ws):
    with connect(ws) as socket:
        init(socket)

        for _ in range(3):
            socket.send_json(FRAME)
            socket.receive_json()

    assert ws.alive_marks == 1
