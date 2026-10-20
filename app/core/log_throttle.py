"""
같은 종류의 로그를 일정 간격에 한 번만 남긴다.
Redis·Kotlin 장애 중에는 추론마다(사용자당 초당 약 6회) 같은 경고가 반복돼 다른 로그를 묻는다.
생략한 횟수는 다음에 남기는 로그에 붙여 장애 규모는 알 수 있게 한다.
"""
import logging
import time

DEFAULT_INTERVAL_SECONDS = 60


class LogThrottle:
    """키마다 interval_seconds에 한 번만 로그를 남긴다. 키 수만큼 메모리를 쓰므로
    키는 메시지 종류처럼 개수가 정해진 값으로 쓰고, 사용자·연결별로 나눌 때는 인스턴스를 따로 만든다."""

    def __init__(self, interval_seconds: float = DEFAULT_INTERVAL_SECONDS):
        self._interval = interval_seconds
        self._last_logged: dict[str, float] = {}
        self._suppressed: dict[str, int] = {}

    def log(self, logger: logging.Logger, level: int, key: str, msg: str, *args, **kwargs) -> None:
        now = time.monotonic()
        last = self._last_logged.get(key)
        if last is not None and now - last < self._interval:
            self._suppressed[key] = self._suppressed.get(key, 0) + 1
            return
        self._last_logged[key] = now
        suppressed = self._suppressed.pop(key, 0)
        if suppressed:
            msg += " (직전 로그 이후 같은 로그 %d건 생략)"
            args += (suppressed,)
        logger.log(level, msg, *args, **kwargs)
