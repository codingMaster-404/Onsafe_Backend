import pytest
from fakeredis import FakeAsyncRedis

from app.ai import buffer


@pytest.fixture
async def fake_redis(monkeypatch):
    """buffer가 쓰는 Redis를 테스트마다 새 인메모리 Redis로 바꾼다."""
    r = FakeAsyncRedis(decode_responses=True)
    monkeypatch.setattr(buffer, "get_redis", lambda: r)
    yield r
    await r.aclose()
