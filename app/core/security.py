import hashlib

from jose import JWTError, jwt
from .config import settings
from .redis import get_redis

# Kotlin JwtProvider.TokenType.ACCESS.claimValue와 같은 값이어야 한다.
ACCESS_TOKEN_TYPE = "access"


def decode_access_token(token: str) -> dict:
    """access 토큰만 통과시킨다.

    같은 시크릿으로 서명된 refresh 토큰(30일)이나 typ이 없는 옛 토큰도 서명·만료 검증은
    통과하므로, typ과 sub(userId)를 함께 확인해 JWTError로 거부한다. iat는 세션 무효화
    판정 기준이라 없으면 거부한다.
    """
    payload = jwt.decode(token, settings.jwt_secret, algorithms=["HS256"])
    if payload.get("typ") != ACCESS_TOKEN_TYPE or not payload.get("sub") or "iat" not in payload:
        raise JWTError("access token이 아닙니다")
    return payload


def blacklist_key(token: str) -> str:
    """Kotlin JwtProvider.blacklistKey와 같은 규칙 — 로그아웃·재발급된 토큰의 Redis 키."""
    return "bl:" + hashlib.sha256(token.encode("utf-8")).hexdigest()


def revocation_key(user_id: str) -> str:
    """Kotlin TokenRevocationStore.key와 같은 규칙 — 사용자 단위 세션 무효화 시각(epoch 초)."""
    return f"token_valid_after:{user_id}"


def is_revoked(issued_at: int, valid_after: str | None) -> bool:
    """Kotlin TokenRevocationStore.isRevoked와 같은 판정.

    무효화와 같은 초에 발급된 토큰도 거부한다(iat <= 무효화 시각). 깨진 값이면 거부(fail-closed).
    """
    if valid_after is None:
        return False
    try:
        return int(issued_at) <= int(valid_after)
    except ValueError:
        return True


async def is_token_rejected(token: str, payload: dict) -> bool:
    """블랙리스트(로그아웃)·세션 무효화(탈퇴·비밀번호 변경·재설정) 여부를 MGET 1회로 확인한다.

    Redis 오류(redis.exceptions.RedisError)는 호출부가 503/1011로 처리한다 — 확인할 수 없는
    토큰을 통과시키지 않는다(fail-closed).
    """
    blacklisted, valid_after = await get_redis().mget(blacklist_key(token), revocation_key(payload["sub"]))
    if blacklisted is not None:
        return True
    return is_revoked(payload["iat"], valid_after)
