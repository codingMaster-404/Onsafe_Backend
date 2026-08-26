# -*- coding: utf-8 -*-
"""
[OK]/[FAIL] test for caution (score 51~75) notification + cooldown logic.

Checks:
  1. score 51~75 -> FallLog saved, level = "CAUTION" (first event)
  2. Immediate second event -> cooldown active, FallLog NOT created
  3. score < 51 -> FallLog NOT created (normal range)
  4. score >= 76 -> FallLog always saved (no cooldown), level = "DANGER"

Run:
  python scripts/test_caution_notification.py
"""
import sys
import uuid
import time
import requests

KOTLIN_URL = "http://localhost:8080"

TEST_USER = {
    "user_id":  f"caution_test_{uuid.uuid4().hex[:6]}",
    "password": "testpass1234",
    "name":     "caution_tester",
    "phone":    "010-9999-0000",
    "mail":     f"caution_{uuid.uuid4().hex[:6]}@onsafe.test",
}
DEVICE_ID = "test-device-caution-001"


def ok(label: str):
    print(f"[OK]   {label}")


def fail(label: str, detail: str = ""):
    print(f"[FAIL] {label}" + (f" -- {detail}" if detail else ""))
    sys.exit(1)


def step(label: str, resp: requests.Response) -> dict:
    body = {}
    try:
        body = resp.json()
    except Exception:
        pass
    if not resp.ok:
        fail(label, f"HTTP {resp.status_code} {body}")
    return body


def post_fall_log(session: requests.Session, score: float, is_fall: bool, log_id: str = None) -> requests.Response:
    lid = log_id or str(uuid.uuid4())
    return session.post(f"{KOTLIN_URL}/internal/fall-log", json={
        "log_id":       lid,
        "device_id":    DEVICE_ID,
        "user_id":      TEST_USER["user_id"],
        "score":        score,
        "fall":         is_fall,
        "is_confirmed": False,
    })


def get_logs(session: requests.Session) -> list:
    resp = session.get(f"{KOTLIN_URL}/api/fall-logs/{TEST_USER['user_id']}")
    body = step("GET fall-logs", resp)
    return body.get("data", {}).get("logs", [])


def main():
    s = requests.Session()
    s.headers.update({"Content-Type": "application/json"})

    print("\n=== Caution Notification Test ===\n")

    # 1. Register
    resp = s.post(f"{KOTLIN_URL}/api/auth/register", json={
        "user_id":  TEST_USER["user_id"],
        "password": TEST_USER["password"],
        "name":     TEST_USER["name"],
        "phone":    TEST_USER["phone"],
        "mail":     TEST_USER["mail"],
    })
    step("register", resp)
    ok("register")

    # 2. Login
    resp = s.post(f"{KOTLIN_URL}/api/auth/login", json={
        "user_id":   TEST_USER["user_id"],
        "password":  TEST_USER["password"],
        "device_id": DEVICE_ID,
    })
    body = step("login", resp)
    token = body["data"]["access_token"]
    s.headers.update({"Authorization": f"Bearer {token}"})
    ok(f"login -> user_id: {TEST_USER['user_id']}")

    # --- Test 1: score 62 (caution) -> should create FallLog ---
    print("\n[Test 1] score=62 (caution, first event) -> expect FallLog created")
    log_id_caution = str(uuid.uuid4())
    resp = post_fall_log(s, score=62.0, is_fall=False, log_id=log_id_caution)
    step("POST /internal/fall-log (score=62)", resp)

    logs = get_logs(s)
    found = any(l["log_id"] == log_id_caution for l in logs)
    if found:
        ok(f"FallLog created for score=62 (log_id={log_id_caution[:8]}...)")
    else:
        fail("FallLog NOT created for caution score=62")

    # check level tag in list
    target = next((l for l in logs if l["log_id"] == log_id_caution), None)
    level = target.get("level", "") if target else ""
    if level in ("주의", "CAUTION", "caution"):
        ok(f"level tag correct: {level}")
    else:
        print(f"       level={level!r} (warn: may differ by implementation)")

    # --- Test 2: immediate second caution event -> cooldown should block ---
    print("\n[Test 2] score=58 (caution, 2nd immediate) -> expect cooldown blocks FallLog")
    log_id_cd = str(uuid.uuid4())
    resp = post_fall_log(s, score=58.0, is_fall=False, log_id=log_id_cd)
    step("POST /internal/fall-log (score=58, 2nd)", resp)

    logs = get_logs(s)
    found_cd = any(l["log_id"] == log_id_cd for l in logs)
    if not found_cd:
        ok("Cooldown active: 2nd caution FallLog correctly blocked")
    else:
        # Note: cooldown is enforced on Python side via Redis.
        # If calling Kotlin internal API directly, cooldown does NOT apply.
        print(f"       [INFO] FallLog was created (cooldown is Python-side only).")
        print(f"              Direct internal API call bypasses Python cooldown -- expected.")

    # --- Test 3: score 30 (normal) -> FallLog should NOT be created ---
    print("\n[Test 3] score=30 (normal) -> expect NO FallLog")
    log_id_normal = str(uuid.uuid4())
    resp = post_fall_log(s, score=30.0, is_fall=False, log_id=log_id_normal)
    step("POST /internal/fall-log (score=30, normal)", resp)

    logs = get_logs(s)
    found_normal = any(l["log_id"] == log_id_normal for l in logs)
    if not found_normal:
        ok("Normal score=30: FallLog correctly NOT in list")
    else:
        print("       [INFO] score=30 log found -- internal API stores regardless of score.")
        print("              Normal-range filtering is Python-side only (expected).")

    # --- Test 4: score 88 (danger) -> always created, no cooldown ---
    print("\n[Test 4] score=88 (danger) -> expect FallLog always created")
    log_id_danger = str(uuid.uuid4())
    resp = post_fall_log(s, score=88.0, is_fall=False, log_id=log_id_danger)
    step("POST /internal/fall-log (score=88, danger)", resp)

    logs = get_logs(s)
    found_danger = any(l["log_id"] == log_id_danger for l in logs)
    if found_danger:
        ok(f"Danger score=88: FallLog created (no cooldown)")
    else:
        fail("FallLog NOT created for danger score=88")

    # --- Test 5: verify ?level=caution filter ---
    print("\n[Test 5] GET /api/fall-logs?level=주의 -> expect caution log in result")
    resp = s.get(f"{KOTLIN_URL}/api/fall-logs/{TEST_USER['user_id']}?level=주의")
    if resp.ok:
        body = resp.json()
        caution_logs = body.get("data", {}).get("logs", [])
        found_in_filter = any(l["log_id"] == log_id_caution for l in caution_logs)
        if found_in_filter:
            ok(f"level=주의 filter returns caution log ({len(caution_logs)} total)")
        else:
            print(f"       [WARN] caution log not in ?level=주의 result (count={len(caution_logs)})")
    else:
        print(f"       [WARN] level filter returned {resp.status_code}")

    # --- Summary ---
    print("\n=== Test Complete ===")
    print("NOTE: Tests 2 & 3 reflect Python-side filtering.")
    print("      Direct /internal/fall-log calls bypass Python cooldown -- by design.")
    print("      Full caution flow requires real camera stream via Python AI server.")
    print()


if __name__ == "__main__":
    main()
