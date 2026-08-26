"""
Firebase Storage 업로드 추상화 레이어.

AWS S3 마이그레이션 시 이 모듈 내부만 교체하면 됨 — 호출부(camera/service.py) 변경 불필요.
현재 구현: Firebase Storage (firebase-admin SDK) — signed URL 방식

업로드 후 GCS 경로(e.g. "fall-videos/{logId}.mp4")를 반환.
Kotlin StorageService가 해당 경로로 V4 signed URL을 온디맨드 발급.
"""
import asyncio
from firebase_admin import storage as fb_storage


# google-cloud-storage 기본 timeout=60초 — 4분 클립(24~120MB) 업로드에는 부족할 수 있어 상향
_UPLOAD_TIMEOUT_SECONDS = 300


def _upload_video_sync(log_id: str, video_bytes: bytes) -> str:
    bucket = fb_storage.bucket()
    blob = bucket.blob(f"fall-videos/{log_id}.mp4")
    blob.upload_from_string(video_bytes, content_type="video/mp4", timeout=_UPLOAD_TIMEOUT_SECONDS)
    return blob.name  # GCS 경로 반환 (e.g. "fall-videos/{logId}.mp4")


async def upload_video(log_id: str, video_bytes: bytes) -> str:
    """낙상 감지 시점 MP4 클립을 Firebase Storage에 업로드하고 GCS 경로를 반환."""
    loop = asyncio.get_event_loop()
    return await loop.run_in_executor(None, _upload_video_sync, log_id, video_bytes)
