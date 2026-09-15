"""
공용 Redis 클라이언트

AI 버퍼(app/ai/buffer.py)와 인증(app/core/security.py)이 같은 연결을 쓴다.
인증 코드가 AI 모듈에 의존하지 않도록 core에 둔다.
"""
from redis.asyncio import Redis
from .config import settings

_redis: Redis | None = None


def get_redis() -> Redis:
    global _redis
    if _redis is None:
        _redis = Redis.from_url(settings.redis_url, decode_responses=True)
    return _redis
