import logging

import pytest

from app.core import log_throttle
from app.core.log_throttle import LogThrottle

logger = logging.getLogger("test.log_throttle")


@pytest.fixture
def clock(monkeypatch):
    """log_throttle이 보는 monotonic 시계. clock.now를 바꿔 시간을 흘린다."""

    class Clock:
        now = 1000.0

    c = Clock()
    monkeypatch.setattr(log_throttle.time, "monotonic", lambda: c.now)
    return c


@pytest.fixture
def records(caplog):
    caplog.set_level(logging.WARNING, logger=logger.name)
    return lambda: [r.getMessage() for r in caplog.records if r.name == logger.name]


def test_logs_once_per_interval_per_key(clock, records):
    throttle = LogThrottle(60)

    for _ in range(3):
        throttle.log(logger, logging.WARNING, "a", "실패 %s", "x")

    assert records() == ["실패 x"]


def test_next_log_after_interval_carries_suppressed_count(clock, records):
    throttle = LogThrottle(60)
    for _ in range(4):
        throttle.log(logger, logging.WARNING, "a", "실패 %s", "x")

    clock.now += 60
    throttle.log(logger, logging.WARNING, "a", "실패 %s", "y")

    assert records() == ["실패 x", "실패 y (직전 로그 이후 같은 로그 3건 생략)"]


def test_suppressed_count_resets_after_logging(clock, records):
    throttle = LogThrottle(60)
    throttle.log(logger, logging.WARNING, "a", "실패")
    throttle.log(logger, logging.WARNING, "a", "실패")
    clock.now += 60
    throttle.log(logger, logging.WARNING, "a", "실패")

    clock.now += 60
    throttle.log(logger, logging.WARNING, "a", "실패")

    assert records()[-1] == "실패"


def test_keys_are_independent(clock, records):
    throttle = LogThrottle(60)

    throttle.log(logger, logging.WARNING, "a", "a 실패")
    throttle.log(logger, logging.WARNING, "b", "b 실패")
    throttle.log(logger, logging.WARNING, "a", "a 실패")

    assert records() == ["a 실패", "b 실패"]


def test_passes_level_and_exc_info(clock, caplog):
    caplog.set_level(logging.ERROR, logger=logger.name)
    throttle = LogThrottle(60)

    try:
        raise ValueError("bug")
    except ValueError:
        throttle.log(logger, logging.ERROR, "a", "실패", exc_info=True)

    record = caplog.records[-1]
    assert record.levelno == logging.ERROR
    assert record.exc_info[0] is ValueError
