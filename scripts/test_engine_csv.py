"""
engine.py 오프라인 CSV 테스트 — 서버/JWT/Firestore 없이 실제 landmark CSV로 추론 검증

MediaPipe 파이프라인이 만든 pose CSV(컬럼: frame, timestamp, kp0_x … kp32_visibility)를
한 프레임씩 infer_landmarks() 에 흘려보내며 윈도우별 status/score 를 출력한다.

사용:
    # 단일 CSV
    python scripts/test_engine_csv.py path/to/clip_pose.csv

    # 여러 CSV 한 번에 (ADL/FALL/손만인식 클립을 나란히 비교)
    python scripts/test_engine_csv.py adl.csv fall.csv hand_only.csv

    # 프레임별 상세 로그까지 보기
    python scripts/test_engine_csv.py clip.csv --verbose

검증 관점:
    · 정상(ADL) 클립      → status=ok, max_score 낮음, fall 미발생
    · 낙상(FALL) 클립     → status=ok, max_score 높음, fall 발생
    · 손만 인식/설치 클립 → status=low_coverage 위주, fall 미발생 (오탐 차단 확인)
"""
import argparse
import sys
from collections import Counter
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent))

import pandas as pd
from app.ai.engine import infer_landmarks, _load_models, CRITICAL_THRESHOLD


def run_csv(csv_path: str, verbose: bool = False) -> None:
    df = pd.read_csv(csv_path)
    device_id = f"csvtest::{Path(csv_path).stem}"  # 클립마다 버퍼 분리

    status_counts: Counter = Counter()
    max_score = 0.0
    fall_frames: list[int] = []

    for idx, row in df.iterrows():
        landmarks = [
            {"x": float(row[f"kp{i}_x"]), "y": float(row[f"kp{i}_y"]),
             "z": float(row[f"kp{i}_z"]), "v": float(row[f"kp{i}_visibility"])}
            for i in range(33)
        ]
        timestamp = float(row["timestamp"]) if "timestamp" in df.columns else idx / 15.0

        result = infer_landmarks(landmarks, device_id, timestamp)
        status_counts[result["status"]] += 1

        if result["status"] == "ok":
            max_score = max(max_score, result["score"])
            if result["fall"]:
                fall_frames.append(int(idx))

        if verbose and result["status"] not in ("warming", "skip"):
            print(f"  frame={int(idx):>4}  status={result['status']:<12} "
                  f"score={result['score']:6.2f}  fall={result['fall']}")

    # ── 클립 요약 ──
    name = Path(csv_path).name
    ok = status_counts.get("ok", 0)
    low = status_counts.get("low_coverage", 0)
    err = status_counts.get("error", 0)
    verdict = "🟥 낙상 감지" if fall_frames else "🟩 낙상 없음"
    print(f"[{name}]")
    print(f"  총 프레임      : {len(df)}")
    print(f"  추론(ok)       : {ok}   저커버리지 차단(low_coverage): {low}   오류(error): {err}")
    print(f"  최대 score     : {max_score:.2f}  (임계 {CRITICAL_THRESHOLD})")
    print(f"  판정           : {verdict}"
          + (f"  (fall 프레임 {len(fall_frames)}개)" if fall_frames else ""))
    print()


def main() -> None:
    parser = argparse.ArgumentParser(description="engine.py 오프라인 CSV 추론 테스트")
    parser.add_argument("csv", nargs="+", help="landmark pose CSV 경로 (여러 개 가능)")
    parser.add_argument("--verbose", action="store_true", help="프레임별 상세 로그 출력")
    args = parser.parse_args()

    _load_models()  # infer_landmarks 는 전역 _model/_scaler 사용 — 먼저 로드
    print(f"[INFO] 모델 로드 완료. CSV {len(args.csv)}개 검증\n")

    for csv_path in args.csv:
        if not Path(csv_path).exists():
            print(f"[WARN] 파일 없음: {csv_path}\n")
            continue
        try:
            run_csv(csv_path, verbose=args.verbose)
        except KeyError as e:
            print(f"[ERROR] {csv_path}: 컬럼 누락 {e} "
                  f"(kp0_x … kp32_visibility 형식인지 확인)\n")


if __name__ == "__main__":
    main()
