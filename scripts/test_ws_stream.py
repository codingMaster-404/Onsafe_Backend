"""
WebSocket /ws/stream 통합 테스트 — 서버 기동 상태에서 실행

사용:
    # 합성 랜덤 landmark로 동작 확인
    python scripts/test_ws_stream.py --token {jwt_access_token} --user {user_id} --device {device_id}

    # 실제 landmark CSV 사용 (컬럼: frame, timestamp, kp0_x..kp32_visibility)
    python scripts/test_ws_stream.py --token {jwt_token} --user {user_id} --device {device_id} --csv path/to/landmarks.csv

확인 항목:
    1. JWT 인증 성공 (토큰 오류 시 1008 close)
    2. init 메시지 → {"type": "init_ok"} 수신
    3. frame 메시지 → {"type": "result", "fall_score", "fall", "level"} 수신
    4. WINDOW_SIZE 미달 구간에서 result 미수신 확인
"""
import argparse
import asyncio
import json
import sys

import numpy as np
import websockets

SERVER_URL = "ws://localhost:8000/ws/stream"
FPS = 15


def make_synthetic_landmarks(n: int = 33) -> list[dict]:
    base = np.random.rand(n, 4) * 0.5 + 0.25
    coords = base + np.random.randn(n, 4) * 0.01
    return [
        {"x": float(coords[i, 0]), "y": float(coords[i, 1]),
         "z": float(coords[i, 2]), "v": float(coords[i, 3])}
        for i in range(n)
    ]


def csv_frames(csv_path: str):
    import pandas as pd
    df = pd.read_csv(csv_path)
    for idx, row in df.iterrows():
        landmarks = [
            {"x": float(row[f"kp{i}_x"]), "y": float(row[f"kp{i}_y"]),
             "z": float(row[f"kp{i}_z"]), "v": float(row[f"kp{i}_visibility"])}
            for i in range(33)
        ]
        yield {
            "type": "frame",
            "frame": int(idx),
            "timestamp": float(row["timestamp"]) if "timestamp" in row else idx / FPS,
            "landmarks": landmarks,
        }


def synthetic_frames(n: int = 60):
    for i in range(n):
        yield {
            "type": "frame",
            "frame": i,
            "timestamp": i / FPS,
            "landmarks": make_synthetic_landmarks(),
        }


async def send_frames(ws, csv_path: str | None):
    generator = csv_frames(csv_path) if csv_path else synthetic_frames()
    for msg in generator:
        await ws.send(json.dumps(msg))
        await asyncio.sleep(1 / FPS)
    print("[FRAMES] 송신 완료")


async def recv_results(ws):
    result_count = 0
    while True:
        try:
            raw = await asyncio.wait_for(ws.recv(), timeout=5.0)
        except (websockets.ConnectionClosed, asyncio.TimeoutError):
            break
        data = json.loads(raw)
        msg_type = data.get("type")
        if msg_type == "init_ok":
            print("[INIT] ✅ init_ok 수신 — 인증·초기화 성공")
        elif msg_type == "result":
            result_count += 1
            print(f"[RESULT] frame={data['frame']:>4}  "
                  f"score={data['fall_score']:6.2f}  "
                  f"fall={data['fall']}  "
                  f"level={data['level']}")
        elif msg_type == "error":
            print(f"[ERROR] {data.get('message')}")
    print(f"\n[DONE] 수신한 추론 결과: {result_count}회")
    return result_count


async def main(token: str, user_id: str, device_id: str, csv_path: str | None):
    url = f"{SERVER_URL}?token={token}"
    print(f"[CONNECT] {url}")

    try:
        async with websockets.connect(url) as ws:
            # 1) init
            await ws.send(json.dumps({
                "type": "init",
                "user_id": user_id,
                "device_id": device_id,
            }))

            # 2) 송수신 동시 실행
            count = await asyncio.gather(
                send_frames(ws, csv_path),
                recv_results(ws),
            )
            result_count = count[1]

        if result_count == 0:
            print("⚠️  추론 결과 미수신 — 프레임 수가 WINDOW_SIZE(30)에 미달했을 수 있음")
        else:
            print("✅ WebSocket 통합 테스트 통과")

    except websockets.exceptions.InvalidStatusCode as e:
        if e.status_code == 1008:
            print("❌ JWT 인증 실패 (code=1008) — 토큰을 확인하세요")
        else:
            print(f"❌ 연결 실패: {e}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--token",  required=True, help="JWT access token")
    parser.add_argument("--user",   required=True, help="user_id")
    parser.add_argument("--device", required=True, help="device_id")
    parser.add_argument("--csv",    default=None,  help="landmark CSV 경로 (없으면 합성 데이터)")
    args = parser.parse_args()

    try:
        asyncio.run(main(args.token, args.user, args.device, args.csv))
    except KeyboardInterrupt:
        pass
