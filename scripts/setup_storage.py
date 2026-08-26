"""
Firebase Storage 초기 설정 스크립트 — gsutil/firebase CLI 없이 실행 가능.

실행:
    python scripts/setup_storage.py

수행 작업:
  1. fall-videos/ 30일 후 자동 삭제 Lifecycle 규칙 적용
  2. 버킷 CORS 설정 (Android 앱 직접 접근 허용)
"""
import os
import sys

# 프로젝트 루트를 모듈 경로에 추가
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import firebase_admin
from firebase_admin import credentials, storage as fb_storage
from google.cloud.storage import Bucket
from dotenv import load_dotenv

load_dotenv()

CREDENTIALS = os.getenv("FIREBASE_CREDENTIALS", "serviceAccountKey.json")
BUCKET_NAME = os.getenv("FIREBASE_STORAGE_BUCKET", "")

if not BUCKET_NAME:
    print("❌ FIREBASE_STORAGE_BUCKET 환경변수가 설정되지 않았습니다.")
    sys.exit(1)


def init():
    cred = credentials.Certificate(CREDENTIALS)
    firebase_admin.initialize_app(cred, {"storageBucket": BUCKET_NAME})


def set_lifecycle(bucket: Bucket) -> None:
    """fall-videos/ 경로 파일을 30일 후 자동 삭제."""
    bucket.lifecycle_rules = []  # 기존 규칙 초기화
    bucket.add_lifecycle_delete_rule(age=30, matches_prefix=["fall-videos/"])
    bucket.patch()
    print("✅ Lifecycle 규칙 적용 완료: fall-videos/ → 30일 후 자동 삭제")


def set_cors(bucket: Bucket) -> None:
    """Android 앱 직접 다운로드 허용 CORS 설정."""
    bucket.cors = [
        {
            "origin": ["*"],
            "method": ["GET", "HEAD"],
            "maxAgeSeconds": 3600,
        }
    ]
    bucket.patch()
    print("✅ CORS 설정 완료: GET/HEAD 허용")


def main():
    print(f"🔧 Firebase Storage 설정 시작 — 버킷: {BUCKET_NAME}")
    init()
    bucket = fb_storage.bucket()

    set_lifecycle(bucket)
    set_cors(bucket)

    print("\n🎉 Storage 초기 설정 완료")
    print("   Storage Rules는 Firebase Console → Storage → Rules 탭에서 storage.rules 내용 붙여넣기 후 배포하세요.")


if __name__ == "__main__":
    main()
