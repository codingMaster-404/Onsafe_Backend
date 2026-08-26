from fastapi import Header
from jose import JWTError
from .security import decode_token
from .exceptions import invalid_token


async def get_current_user_id(authorization: str = Header(..., alias="Authorization")) -> str:
    if not authorization.startswith("Bearer "):
        raise invalid_token()
    token = authorization[7:]
    try:
        payload = decode_token(token)
        return payload["sub"]
    except (JWTError, KeyError):
        raise invalid_token()
