import logging
import os

import firebase_admin
from firebase_admin import credentials, firestore_async

from .config import settings

logger = logging.getLogger(__name__)

_firebase_app: firebase_admin.App | None = None


def init_firebase() -> None:
    global _firebase_app
    if _firebase_app is not None:
        return

    options = {}
    if settings.firebase_storage_bucket:
        options["storageBucket"] = settings.firebase_storage_bucket

    # 1) 명시적 파일 경로가 설정되고 실제로 존재하면 파일 사용 (로컬/docker-compose)
    cred_path = settings.firebase_credentials
    if cred_path and os.path.isfile(cred_path):
        logger.info("Firebase credentials: loading from file %s", cred_path)
        cred = credentials.Certificate(cred_path)
    else:
        # 2) 그 외에는 ADC 사용 — Cloud Run 등에서 메타데이터 서버로부터 자동 획득
        logger.info("Firebase credentials: using Application Default Credentials (ADC)")
        cred = credentials.ApplicationDefault()

    _firebase_app = firebase_admin.initialize_app(cred, options or None)


def get_firestore():
    return firestore_async.client()