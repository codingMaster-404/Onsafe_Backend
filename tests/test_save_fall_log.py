import json

import httpx
import pytest

from app.domain.camera import service

_RealAsyncClient = httpx.AsyncClient


@pytest.fixture
def kotlin(monkeypatch):
    """service가 만드는 httpx.AsyncClient를 MockTransport로 바꾼다.
    kotlin.respond에 응답(또는 던질 예외)을 정하고, kotlin.requests로 보낸 요청을 확인한다."""

    class FakeKotlin:
        def __init__(self):
            self.requests: list[httpx.Request] = []
            self.respond = httpx.Response(200, json={"success": True})

        def handler(self, request: httpx.Request) -> httpx.Response:
            self.requests.append(request)
            if isinstance(self.respond, Exception):
                raise self.respond
            return self.respond

    fake = FakeKotlin()
    monkeypatch.setattr(
        service.httpx, "AsyncClient",
        lambda *a, **kw: _RealAsyncClient(*a, transport=httpx.MockTransport(fake.handler), **kw),
    )
    return fake


async def _save():
    return await service._save_fall_log("u1", "d1", 80.0, True, video_bytes=None)


async def test_2xx_returns_log_id_sent_to_kotlin(kotlin):
    log_id = await _save()

    assert log_id is not None
    body = json.loads(kotlin.requests[0].content)
    assert body["log_id"] == log_id


@pytest.mark.parametrize("status", [400, 403, 404, 500, 503])
async def test_non_2xx_returns_none(kotlin, status):
    kotlin.respond = httpx.Response(status, json={"success": False, "message": "err"})

    assert await _save() is None


@pytest.mark.parametrize("error", [
    httpx.ReadTimeout("timed out"),
    httpx.ConnectError("connection refused"),
], ids=["timeout", "connect_error"])
async def test_transport_error_returns_none(kotlin, error):
    kotlin.respond = error

    assert await _save() is None


async def test_non_2xx_logs_status_and_truncated_body(kotlin, caplog):
    kotlin.respond = httpx.Response(400, text="x" * 500)

    await _save()

    record = next(r for r in caplog.records if "저장 실패" in r.getMessage())
    assert "status=400" in record.getMessage()
    assert "x" * 200 in record.getMessage()
    assert "x" * 201 not in record.getMessage()


async def test_request_uses_10s_timeout_and_keeps_contract(kotlin):
    await _save()

    req = kotlin.requests[0]
    assert req.url.path == "/internal/fall-log"
    assert req.extensions["timeout"] == {"connect": 10.0, "read": 10.0, "write": 10.0, "pool": 10.0}
    assert "x-internal-auth" in req.headers
    assert set(json.loads(req.content)) == {
        "log_id", "device_id", "user_id", "score", "fall", "is_confirmed", "video_url",
    }
