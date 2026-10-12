import base64
import logging

from fastapi import Header
from jose import JWTError
from redis.exceptions import RedisError
from .firebase import get_firestore
from .security import decode_access_token, is_token_rejected
from .exceptions import forbidden, invalid_token, service_unavailable

logger = logging.getLogger(__name__)

_GUARDIAN_LINKS = "guardian_links"


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

    쓰기(기기 등록 등)처럼 본인만 허용해야 하는 경로용. 보호자의 피보호자 조회가 필요한
    읽기 경로는 require_owner_or_guardian을 쓴다.
    """
    if path_user_id != current_user_id:
        raise forbidden()


def _guardian_link_doc_id(guardian_user_id: str, elder_user_id: str) -> str:
    """Kotlin GuardianLinkRepository.docId와 동일한 규칙 — 각 파트를 UTF-8 → URL-safe
    Base64(패딩 없음)로 인코딩해 ":"로 잇는다. 규칙이 어긋나면 존재하는 관계도 403이 된다."""
    def enc(s: str) -> str:
        return base64.urlsafe_b64encode(s.encode("utf-8")).rstrip(b"=").decode("ascii")
    return f"{enc(guardian_user_id)}:{enc(elder_user_id)}"


async def require_owner_or_guardian(path_user_id: str, current_user_id: str) -> None:
    """본인 또는 연결된 보호자가 아니면 403 — Kotlin AccessGuard.requireOwnerOrGuardian과 같은 기준.

    보호자 앱 홈은 연결된 피보호자의 기기를 표시하므로 피보호자 userId로 조회한다.
    """
    if path_user_id == current_user_id:
        return
    doc_id = _guardian_link_doc_id(current_user_id, path_user_id)
    doc = await get_firestore().collection(_GUARDIAN_LINKS).document(doc_id).get()
    if not doc.exists:
        raise forbidden()
