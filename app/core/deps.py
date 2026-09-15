import logging

from fastapi import Header
from jose import JWTError
from redis.exceptions import RedisError
from .security import decode_access_token, is_token_rejected
from .exceptions import forbidden, invalid_token, service_unavailable

logger = logging.getLogger(__name__)


async def get_current_user_id(authorization: str = Header(..., alias="Authorization")) -> str:
    if not authorization.startswith("Bearer "):
        raise invalid_token()
    token = authorization[7:]
    try:
        payload = decode_access_token(token)
    except JWTError:
        raise invalid_token()
    try:
        rejected = await is_token_rejected(token, payload)
    except RedisError as e:
        logger.error("토큰 블랙리스트·무효화 조회 실패: %s", e)
        raise service_unavailable()
    if rejected:
        raise invalid_token()
    return payload["sub"]


def require_same_user(path_user_id: str, current_user_id: str) -> None:
    """경로의 user_id가 토큰 사용자와 다르면 403.

    앱은 Python API를 본인 userId로만 호출한다(완료 문서 K4·D8). 보호자가 다른 사용자를
    조회하는 흐름이 생기면 Kotlin AccessGuard와 같은 보호자 인가를 다시 검토한다.
    """
    if path_user_id != current_user_id:
        raise forbidden()
