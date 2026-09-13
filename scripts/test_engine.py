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
from app.ai.engine import (
    infer_landmarks, _load_models, WINDOW_SIZE, STRIDE, FEATURE_COLUMNS,
)


def make_synthetic_landmarks(n: int = 33, visibility: float = 0.95) -> list[dict]:
    """정적 포즈 기반 랜덤 landmark 33개 생성.
    visibility 는 관절별 신뢰도 — 기본값은 커버리지 게이트를 통과하는 정상 검출 수준."""
    base = np.random.rand(n, 3) * 0.5 + 0.25
    noise = np.random.randn(n, 3) * 0.01
    coords = base + noise
    return [
        {"x": float(coords[i, 0]), "y": float(coords[i, 1]),
         "z": float(coords[i, 2]), "v": visibility}
        for i in range(n)
    ]


def run():
    _load_models()  # infer_landmarks 는 전역 _model/_scaler 를 사용 — 먼저 로드

    fps = 15.0

    print(f"[INFO] WINDOW_SIZE={WINDOW_SIZE}, STRIDE={STRIDE}, FEATURE_COLUMNS={len(FEATURE_COLUMNS)}개")
    print(f"[INFO] {WINDOW_SIZE + STRIDE}프레임 송신 후 첫 추론 결과 예상\n")

    # ── 케이스 1: 정상 커버리지 → 추론 결과가 반환되어야 함 ──────────────────
    result_count = 0
    for i in range(WINDOW_SIZE + STRIDE + 5):
        result = infer_landmarks(make_synthetic_landmarks(), "test_good", i / fps)
        if result["features"]:
            result_count += 1
            print(f"[RESULT] frame={i:>3}  score={result['score']:6.2f}  fall={result['fall']}")
        else:
            print(f"[SKIP]   frame={i:>3}  status={result['status']}")

    print(f"\n[DONE] 정상 커버리지 추론 결과: {result_count}회")
    assert result_count >= 1, "❌ 정상 커버리지인데 추론 결과가 한 번도 반환되지 않음"

    # ── 케이스 2: 손만 인식(하체 미검출) → low_coverage 로 걸러져야 함 ────────
    # 피처에 쓰이는 하체 관절(엉덩이·무릎·발목)의 visibility 를 0 으로 → 커버리지 게이트 차단
    lower_body = {23, 24, 25, 26, 27, 28, 31, 32}
    ok_count = 0
    for i in range(WINDOW_SIZE + STRIDE + 5):
        lms = make_synthetic_landmarks()
        for j in lower_body:
            lms[j]["v"] = 0.0
        result = infer_landmarks(lms, "test_hand_only", i / fps)
        if result["status"] == "ok":
            ok_count += 1

    print(f"[DONE] 손만 인식 시 ok(=오탐 경로) 추론: {ok_count}회")
    assert ok_count == 0, "❌ 손만 인식되는 저커버리지 입력이 추론을 통과함(오탐 위험)"

    print("✅ engine.py 단위 테스트 통과 (정상 추론 + 저커버리지 차단)")


if __name__ == "__main__":
    run()
