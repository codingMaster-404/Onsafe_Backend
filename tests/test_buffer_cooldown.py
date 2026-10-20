import pytest
from redis.exceptions import ConnectionError as RedisConnectionError

from app.ai import buffer

COOLDOWNS = [
    pytest.param(buffer.check_caution_cooldown, buffer.shorten_caution_cooldown, "caution_cd", id="caution"),
    pytest.param(buffer.check_danger_cooldown, buffer.shorten_danger_cooldown, "danger_cd", id="danger"),
]


@pytest.mark.parametrize("check, shorten, prefix", COOLDOWNS)
async def test_shorten_keeps_cooldown_for_retry_interval_only(fake_redis, check, shorten, prefix):
    assert await check("u1") is True
    assert await fake_redis.ttl(f"{prefix}:u1") > buffer.SAVE_RETRY_SECONDS

    await shorten("u1")

    assert 0 < await fake_redis.ttl(f"{prefix}:u1") <= buffer.SAVE_RETRY_SECONDS
    # 재시도 간격 동안은 여전히 쿨다운 중이다
    assert await check("u1") is False


@pytest.mark.parametrize("check, shorten, prefix", COOLDOWNS)
async def test_after_retry_interval_next_check_acquires_again(fake_redis, check, shorten, prefix):
    await check("u1")
    await shorten("u1", retry_after_seconds=1)

    await fake_redis.delete(f"{prefix}:u1")  # TTL 만료와 같은 상태

    assert await check("u1") is True


@pytest.mark.parametrize("check, shorten, prefix", COOLDOWNS)
async def test_shorten_only_touches_that_user(fake_redis, check, shorten, prefix):
    await check("u1")
    await check("u2")

    await shorten("u1")

    assert await fake_redis.ttl(f"{prefix}:u2") > buffer.SAVE_RETRY_SECONDS


async def test_shorten_does_not_touch_other_cooldown_kind(fake_redis):
    await buffer.check_caution_cooldown("u1")
    await buffer.check_danger_cooldown("u1")

    await buffer.shorten_danger_cooldown("u1")

    assert await fake_redis.ttl("caution_cd:u1") > buffer.SAVE_RETRY_SECONDS
    assert await fake_redis.ttl("danger_cd:u1") <= buffer.SAVE_RETRY_SECONDS


@pytest.mark.parametrize("check, shorten, prefix", COOLDOWNS)
async def test_shorten_propagates_redis_error(monkeypatch, check, shorten, prefix):
    # S2: 실패는 buffer에서 삼키지 않고 호출부(camera/service.py)로 올린다
    class BrokenRedis:
        async def set(self, *args, **kwargs):
            raise RedisConnectionError("redis down")

    monkeypatch.setattr(buffer, "get_redis", lambda: BrokenRedis())

    with pytest.raises(RedisConnectionError):
        await shorten("u1")
