import time

import pytest
from redis.exceptions import ConnectionError as RedisConnectionError

from app.ai import buffer
from app.ai.engine import CRITICAL_THRESHOLD, WARNING_THRESHOLD
from app.domain.camera import service

DANGER_SCORE = CRITICAL_THRESHOLD + 5
CAUTION_SCORE = WARNING_THRESHOLD + 5


class BrokenRedis:
    """모든 명령이 연결 오류를 내는 Redis."""

    def __getattr__(self, name):
        async def fail(*args, **kwargs):
            raise RedisConnectionError("redis down")
        return fail


@pytest.fixture
def frame(monkeypatch):
    """추론 결과·Kotlin 호출을 대신한다. frame.score로 점수를, frame.save_ok로 저장 성공 여부를 정한다."""

    class Frame:
        def __init__(self):
            self.score = DANGER_SCORE
            self.save_ok = True
            self.saves = 0

        async def __call__(self):
            return await service.process_frame([], 0.0, "u1", "d1")

    f = Frame()

    async def infer(landmarks, device_id, timestamp):
        return {"status": "ok", "score": f.score, "fall": False, "features": {"neck_angle": 1.0}}

    async def update_realtime(user_id, score, level):
        pass

    async def save_fall_log(user_id, device_id, score, fall, video_bytes):
        f.saves += 1
        return f"log-{f.saves}" if f.save_ok else None

    monkeypatch.setattr(service, "infer_landmarks_async", infer)
    monkeypatch.setattr(service, "_update_realtime", update_realtime)
    monkeypatch.setattr(service, "_save_fall_log", save_fall_log)
    monkeypatch.setattr(service, "_local_cooldowns", {})
    return f


@pytest.fixture
def broken_redis(monkeypatch):
    monkeypatch.setattr(buffer, "get_redis", lambda: BrokenRedis())


# ── Redis 정상 ────────────────────────────────────────────────

@pytest.mark.parametrize("score, prefix, ttl", [
    (DANGER_SCORE, "danger_cd", buffer.DANGER_COOLDOWN_SECONDS),
    (CAUTION_SCORE, "caution_cd", buffer.CAUTION_COOLDOWN_SECONDS),
], ids=["danger", "caution"])
async def test_save_success_keeps_full_cooldown(fake_redis, frame, score, prefix, ttl):
    frame.score = score

    first = await frame()
    second = await frame()

    assert first.log_id == "log-1"
    assert second.log_id is None
    assert frame.saves == 1
    assert await fake_redis.ttl(f"{prefix}:u1") > ttl - 5


@pytest.mark.parametrize("score, prefix", [
    (DANGER_SCORE, "danger_cd"),
    (CAUTION_SCORE, "caution_cd"),
], ids=["danger", "caution"])
async def test_save_failure_shortens_cooldown_to_retry_interval(fake_redis, frame, score, prefix):
    frame.score = score
    frame.save_ok = False

    first = await frame()
    second = await frame()  # 재시도 간격 안에서는 다시 호출하지 않는다

    assert first.log_id is None
    assert second.log_id is None
    assert frame.saves == 1
    assert 0 < await fake_redis.ttl(f"{prefix}:u1") <= buffer.SAVE_RETRY_SECONDS


async def test_retry_after_failure_saves_once_kotlin_recovers(fake_redis, frame):
    frame.save_ok = False
    await frame()

    await fake_redis.delete("danger_cd:u1")  # 재시도 간격(30초) 만료와 같은 상태
    frame.save_ok = True
    result = await frame()

    assert result.log_id == "log-2"
    assert await fake_redis.ttl("danger_cd:u1") > buffer.DANGER_COOLDOWN_SECONDS - 5


async def test_shorten_failure_does_not_break_frame(fake_redis, frame, monkeypatch):
    async def broken_shorten(user_id):
        raise RedisConnectionError("redis down")

    monkeypatch.setitem(
        service._COOLDOWNS, "danger",
        (buffer.check_danger_cooldown, broken_shorten, buffer.DANGER_COOLDOWN_SECONDS),
    )
    frame.save_ok = False

    result = await frame()

    assert result.log_id is None
    assert result.level == "위험"


# ── Redis 장애 (fail-open + 메모리 쿨다운) ─────────────────────

async def test_redis_down_still_reaches_judgement_with_raw_score(broken_redis, frame):
    result = await frame()

    assert result.score == DANGER_SCORE
    assert result.level == "위험"
    assert result.log_id == "log-1"


async def test_redis_down_local_cooldown_blocks_repeat_alerts(broken_redis, frame):
    first = await frame()
    second = await frame()
    third = await frame()

    assert first.log_id == "log-1"
    assert second.log_id is None and third.log_id is None
    assert frame.saves == 1


async def test_redis_down_local_cooldown_is_per_kind_and_user(broken_redis, frame):
    frame.score = CAUTION_SCORE
    await frame()
    frame.score = DANGER_SCORE
    await frame()
    await service.process_frame([], 0.0, "u2", "d2")

    assert frame.saves == 3


async def test_redis_down_save_failure_retries_after_interval(broken_redis, frame):
    frame.save_ok = False
    await frame()

    expires_at = service._local_cooldowns[("danger", "u1")]
    assert expires_at - time.monotonic() <= buffer.SAVE_RETRY_SECONDS
    assert (await frame()).log_id is None
    assert frame.saves == 1

    service._local_cooldowns[("danger", "u1")] = time.monotonic() - 1  # 재시도 간격 만료
    frame.save_ok = True

    assert (await frame()).log_id == "log-2"


async def test_local_cooldown_prunes_expired_entries(broken_redis, frame):
    service._local_cooldowns[("danger", "old-user")] = time.monotonic() - 1

    await frame()

    assert ("danger", "old-user") not in service._local_cooldowns


async def test_redis_down_on_inference_error_path_returns_neutral(broken_redis, monkeypatch):
    async def infer_error(landmarks, device_id, timestamp):
        return {"status": "error"}

    monkeypatch.setattr(service, "infer_landmarks_async", infer_error)

    result = await service.process_frame([], 0.0, "u1", "d1")

    assert result.score == 0.0
    assert result.level is None


async def test_non_redis_error_is_not_swallowed(frame, monkeypatch):
    # RedisError만 잡는다 — 다른 예외는 버그로 보고 프레임 단위 처리(5단계)에 맡긴다
    class BuggyRedis:
        def __getattr__(self, name):
            async def fail(*args, **kwargs):
                raise ValueError("bug")
            return fail

    monkeypatch.setattr(buffer, "get_redis", lambda: BuggyRedis())

    with pytest.raises(ValueError):
        await frame()
