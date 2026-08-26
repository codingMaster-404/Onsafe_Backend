# -*- coding: utf-8 -*-
"""
Storage API integration test script
- video / signed URL endpoints

Setup:
  1. docker compose up -d
  2. pip install requests

Run:
  python scripts/test_storage_api.py
"""
import argparse
import sys
import uuid
import requests

DEFAULT_KOTLIN = "http://localhost:8080"
DEFAULT_PYTHON  = "http://localhost:8000"

TEST_USER = {
    "user_id":  f"test_{uuid.uuid4().hex[:8]}",
    "password": "testpass1234",
    "name":     "testuser",
    "phone":    "010-1234-5678",
    "mail":     f"test_{uuid.uuid4().hex[:6]}@onsafe.test",
}


def step(label: str, resp: requests.Response) -> dict:
    body = resp.json()
    mark = "[OK]" if resp.ok else "[FAIL]"
    print(f"{mark} [{resp.status_code}] {label}")
    if not resp.ok:
        print(f"       {body}")
        sys.exit(1)
    return body


def main(kotlin_url: str) -> None:
    s = requests.Session()
    s.headers.update({"Content-Type": "application/json"})

    print("\n=== On-safe Storage API Test ===\n")

    # 1. register
    resp = s.post(f"{kotlin_url}/api/auth/register", json={
        "user_id":  TEST_USER["user_id"],
        "password": TEST_USER["password"],
        "name":     TEST_USER["name"],
        "phone":    TEST_USER["phone"],
        "mail":     TEST_USER["mail"],
    })
    step("register", resp)

    # 2. login -> JWT
    resp = s.post(f"{kotlin_url}/api/auth/login", json={
        "user_id":   TEST_USER["user_id"],
        "password":  TEST_USER["password"],
        "device_id": "test-device-001",
    })
    body = step("login", resp)
    token   = body["data"]["access_token"]
    user_id = TEST_USER["user_id"]
    s.headers.update({"Authorization": f"Bearer {token}"})
    print(f"       user_id: {user_id}\n")

    # 3. insert FallLog with video_url via internal API
    log_id         = str(uuid.uuid4())
    fake_video_url = "fall-videos/test-video.mp4"

    resp = requests.post(f"{kotlin_url}/internal/fall-log", json={
        "log_id":       log_id,
        "device_id":    "test-device-001",
        "user_id":      user_id,
        "score":        85.0,
        "fall":         True,
        "is_confirmed": False,
        "video_url":    fake_video_url,
    })
    step("insert FallLog (internal)", resp)
    print(f"       log_id:    {log_id}")
    print(f"       video_url: {fake_video_url}\n")

    # 4. fall-log list -> check has_video
    resp = s.get(f"{kotlin_url}/api/fall-logs/{user_id}")
    body = step("GET fall-logs list", resp)
    logs   = body["data"]["logs"]
    target = next((l for l in logs if l["log_id"] == log_id), None)
    if target is None:
        print("[FAIL] inserted log_id not found in list")
        sys.exit(1)
    has_video = target.get("has_video", False)
    video_ok  = "[OK]" if has_video else "[FAIL]"
    print(f"       has_video: {has_video} {video_ok}\n")

    # 5. GET /video -> signed URL
    resp = s.get(f"{kotlin_url}/api/fall-logs/{user_id}/{log_id}/video")
    body = step("GET /video (signed URL)", resp)
    signed_url = body["data"].get("signed_url", "")
    preview = signed_url[:90] + ("..." if len(signed_url) > 90 else "")
    print(f"       signed_url: {preview}\n")

    # 6. FallLog without video_url -> /video should return 404
    no_video_id = str(uuid.uuid4())
    requests.post(f"{kotlin_url}/internal/fall-log", json={
        "log_id":       no_video_id,
        "device_id":    "test-device-001",
        "user_id":      user_id,
        "score":        60.0,
        "fall":         False,
        "is_confirmed": False,
    })
    resp  = s.get(f"{kotlin_url}/api/fall-logs/{user_id}/{no_video_id}/video")
    is_404 = resp.status_code == 404
    mark   = "[OK]" if is_404 else "[FAIL]"
    print(f"{mark} [{resp.status_code}] no-video log -> expect 404\n")

    # result
    print("=== Test Done ===\n")
    print("NOTE: signed URL points to a non-existent GCS file (fake path).")
    print("      Use real camera streaming to test actual video download.\n")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--kotlin-url", default=DEFAULT_KOTLIN)
    args = parser.parse_args()
    main(args.kotlin_url)
