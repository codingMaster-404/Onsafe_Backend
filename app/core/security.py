from jose import JWTError, jwt
from .config import settings

# Kotlin JwtProvider.TokenType.ACCESS.claimValue와 같은 값이어야 한다.
ACCESS_TOKEN_TYPE = "access"


def decode_access_token(token: str) -> dict:
    """access 토큰만 통과시킨다.

    같은 시크릿으로 서명된 refresh 토큰(30일)이나 typ이 없는 옛 토큰도 서명·만료 검증은
    통과하므로, typ과 sub(userId)를 함께 확인해 JWTError로 거부한다.
    """
    payload = jwt.decode(token, settings.jwt_secret, algorithms=["HS256"])
    if payload.get("typ") != ACCESS_TOKEN_TYPE or not payload.get("sub"):
        raise JWTError("access token이 아닙니다")
    return payload
