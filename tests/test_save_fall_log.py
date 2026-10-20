import json
import logging

import httpx
import pytest

from app.core.log_throttle import LogThrottle
from app.domain.camera import service

_RealAsyncClient = httpx.AsyncClient


@pytest.fixture
def kotlin(monkeypatch):
    """service가 만드는 httpx.AsyncClient를 MockTransport로 바꾼다.
    kotlin.respond에 응답(또는 던질 예외)을 정하고, kotlin.requests로 보낸 요청을 확인한다."""

    class FakeKotlin:
        def __init__(self):
            self.requests: list[httpx.Request] = []
            self.respond = httpx.Response(200, json={"success": True, "message": "낙상 로그 저장 완료", "data": {"saved": True}})

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


async def test_saved_returns_log_id_sent_to_kotlin(kotlin):
    result = await _save()

    assert result.log_id is not None
    assert result.retry is False
    body = json.loads(kotlin.requests[0].content)
    assert body["log_id"] == result.log_id


@pytest.mark.parametrize("status", [400, 403, 404, 500, 503])
async def test_non_2xx_returns_none(kotlin, status):
    kotlin.respond = httpx.Response(status, json={"success": False, "message": "err"})

    assert await _save() == service.FallLogSaveResult(log_id=None, retry=True)


@pytest.mark.parametrize("error", [
    httpx.ReadTimeout("timed out"),
    httpx.ConnectError("connection refused"),
], ids=["timeout", "connect_error"])
async def test_transport_error_returns_none(kotlin, error):
    kotlin.respond = error

    assert await _save() == service.FallLogSaveResult(log_id=None, retry=True)


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


# ── Kotlin이 저장을 건너뛴 경우 (F2·F3·S7) ─────────────────────

async def test_saved_false_returns_none_without_retry(kotlin, caplog):
    caplog.set_level(logging.INFO, logger=service.__name__)
    kotlin.respond = httpx.Response(200, json={
        "success": True, "message": "낙상 로그 저장 건너뜀 (계정 없음 또는 연결된 보호자 없음)", "data": {"saved": False},
    })

    assert await _save() == service.FallLogSaveResult(log_id=None, retry=False)
    assert any("저장 건너뜀" in r.getMessage() for r in caplog.records)


@pytest.mark.parametrize("response", [
    httpx.Response(200, json={"success": True, "message": "낙상 로그 저장 완료"}),
    httpx.Response(200, json={"success": True, "data": None}),
    httpx.Response(200, json={"success": True, "data": {"saved": "false"}}),
    httpx.Response(200, json=["unexpected"]),
    httpx.Response(200, text="not json"),
    httpx.Response(204),
], ids=["no_data_old_kotlin", "data_null", "saved_string", "not_object", "not_json", "empty_body"])
async def test_anything_but_json_false_counts_as_saved(kotlin, response):
    kotlin.respond = response

    result = await _save()

    assert result.log_id is not None
    assert result.retry is False


async def test_realtime_failure_logged_once_per_interval(kotlin, caplog, monkeypatch):
    # Kotlin 장애 중 추론마다 /internal/realtime 실패가 쌓이지 않게 한다(S14)
    monkeypatch.setattr(service, "_log_throttle", LogThrottle())
    kotlin.respond = httpx.ConnectError("connection refused")

    for _ in range(3):
        await service._update_realtime("u1", 50.0, "주의")

    assert sum("/internal/realtime 호출 실패" in r.getMessage() for r in caplog.records) == 1
