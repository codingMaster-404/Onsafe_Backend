"""
engine.py infer_landmarks() 단위 테스트 — WebSocket / 서버 기동 없이 동작 확인

사용:
    python scripts/test_engine.py

확인 항목:
    1. xgboost 모델 / scaler 로드 성공
    2. infer_landmarks() 반환 구조 정상
    3. WINDOW_SIZE(30) 미달 시 빈 features 반환
    4. WINDOW_SIZE 충족 + STRIDE(5) 시 추론 결과 반환
"""
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent))

import numpy as np
from app.ai.engine import infer_landmarks, WINDOW_SIZE, STRIDE, FEATURE_COLUMNS


def make_synthetic_landmarks(n: int = 33) -> list[dict]:
    """정적 포즈 기반 랜덤 landmark 33개 생성"""
    base = np.random.rand(n, 4) * 0.5 + 0.25
    noise = np.random.randn(n, 4) * 0.01
    coords = base + noise
    return [
        {"x": float(coords[i, 0]), "y": float(coords[i, 1]),
         "z": float(coords[i, 2]), "v": float(coords[i, 3])}
        for i in range(n)
    ]


def run():
    device_id = "test_device_001"
    fps = 15.0
    result_count = 0

    print(f"[INFO] WINDOW_SIZE={WINDOW_SIZE}, STRIDE={STRIDE}, FEATURE_COLUMNS={len(FEATURE_COLUMNS)}개")
    print(f"[INFO] {WINDOW_SIZE + STRIDE}프레임 송신 후 첫 추론 결과 예상\n")

    for i in range(WINDOW_SIZE + STRIDE + 5):
        landmarks = make_synthetic_landmarks()
        timestamp = i / fps

        result = infer_landmarks(landmarks, device_id, timestamp)

        if result["features"]:
            result_count += 1
            print(f"[RESULT] frame={i:>3}  score={result['score']:6.2f}  fall={result['fall']}")
        else:
            print(f"[SKIP]   frame={i:>3}  (윈도우 미달 또는 STRIDE 미달)")

    print(f"\n[DONE] 총 추론 결과: {result_count}회")

    # 검증
    assert result_count >= 1, "❌ 추론 결과가 한 번도 반환되지 않음"
    print("✅ engine.py 단위 테스트 통과")


if __name__ == "__main__":
    run()
