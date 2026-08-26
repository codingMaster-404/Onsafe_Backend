"""
AI 낙상 감지 엔진 — OnSafe/ai-server/main.py 파이프라인 기반
landmark JSON → 30프레임 슬라이딩 윈도우 → XGBoost 추론

학습 파이프라인 대응:
  Step2 → _step2_resolve_nan()       (4.Nan_Resolution.ipynb)
  Step3 → _step3_smoothing_savgol()  (5.Smoothing_SGV.ipynb)
  Step4 → _step4_pose_normalize()    (6.Scaling.ipynb)
  Step5 → _step5_make_features()     (7.Make_Feature.ipynb)
  Step6 → _step6_scale()             (Make_AI.ipynb)
"""
import asyncio
import logging
from collections import deque
from pathlib import Path

import joblib
import numpy as np
import pandas as pd
from scipy.signal import savgol_filter

logger = logging.getLogger(__name__)

# ── 경로 상수 ──────────────────────────────────────────────────────────────────
_PKL_DIR = Path(__file__).parent.parent.parent / "pkl"

# ── 추론 파라미터 ──────────────────────────────────────────────────────────────
WINDOW_SIZE          = 30    # 슬라이딩 윈도우 프레임 수
STRIDE               = 5     # 추론 호출 간격 (프레임)
WARNING_THRESHOLD    = 50.0  # 주의
CRITICAL_THRESHOLD   = 75.0  # 위험
SCORE_SMOOTH_SECONDS = 2.0   # score 출력 스무딩 구간 (2026-08-05 2.5초→2초 보정)

# ── 관절 트리플 / 피처 순서 (학습 파이프라인과 1:1 동일) ──────────────────────
_JOINT_TRIPLETS = [
    ('neck',             0, 11, 12),
    ('shoulder_balance', 11,  0, 12),
    ('shoulder_left',   23, 11, 13),
    ('shoulder_right',  24, 12, 14),
    ('elbow_left',      11, 13, 15),
    ('elbow_right',     12, 14, 16),
    ('hip_left',        11, 23, 25),
    ('hip_right',       12, 24, 26),
    ('knee_left',       23, 25, 27),
    ('knee_right',      24, 26, 28),
    ('ankle_left',      25, 27, 31),
    ('ankle_right',     26, 28, 32),
    ('torso_left',       0, 11, 23),
    ('torso_right',      0, 12, 24),
    ('spine',            0, 23, 24),
]
_JOINTS_ORDER = [
    'neck', 'shoulder_balance',
    'shoulder_left', 'shoulder_right',
    'elbow_left', 'elbow_right',
    'hip_left', 'hip_right',
    'knee_left', 'knee_right',
    'torso_left', 'torso_right', 'spine',  # ankle보다 앞
    'ankle_left', 'ankle_right',
]
FEATURE_COLUMNS: list[str] = []
for _j in _JOINTS_ORDER:
    FEATURE_COLUMNS += [
        f'{_j}_angle',
        f'{_j}_angular_velocity',
        f'{_j}_angular_acceleration',
    ]
FEATURE_COLUMNS += ['center_distance', 'center_speed']
assert len(FEATURE_COLUMNS) == 47, f"Feature 개수 불일치: {len(FEATURE_COLUMNS)}"

# ── 싱글턴 모델 ────────────────────────────────────────────────────────────────
_model  = None
_scaler = None


def _load_models() -> None:
    global _model, _scaler
    model_path  = _PKL_DIR / "xgb_model.pkl"
    scaler_path = _PKL_DIR / "scaler.pkl"
    if not model_path.exists() or not scaler_path.exists():
        raise RuntimeError(f"모델/스케일러 파일 없음: {model_path}, {scaler_path}")
    _model  = joblib.load(model_path)
    _scaler = joblib.load(scaler_path)


# ── 기기별 프레임 버퍼 (Method A — deque 단일 책임) ───────────────────────────
_frame_buffers: dict[str, deque] = {}
_frame_counts:  dict[str, int]   = {}


def _get_buffer(device_id: str) -> deque:
    if device_id not in _frame_buffers:
        _frame_buffers[device_id] = deque(maxlen=WINDOW_SIZE)
    return _frame_buffers[device_id]


# ── 기기별 score 히스토리 (2~3초 출력 스무딩용, device_id별 인메모리) ────────────
_score_history: dict[str, deque] = {}
_SCORE_HISTORY_MAXLEN = 60  # 고fps 환경에서도 SCORE_SMOOTH_SECONDS 구간을 넉넉히 담기 위한 상한


def _smooth_score(device_id: str, timestamp: float, instant_score: float) -> float:
    """윈도우(30프레임=~1초) 평균으로 나온 instant_score를 SCORE_SMOOTH_SECONDS(2초) 구간으로 추가 평활화한다.
    device_id별 (timestamp, score) 이력을 인메모리로 들고, 현재 시각 기준 최근 구간만 평균 낸다."""
    if device_id not in _score_history:
        _score_history[device_id] = deque(maxlen=_SCORE_HISTORY_MAXLEN)
    hist = _score_history[device_id]
    hist.append((timestamp, instant_score))

    cutoff = timestamp - SCORE_SMOOTH_SECONDS
    recent = [s for t, s in hist if t >= cutoff]
    return sum(recent) / len(recent) if recent else instant_score


# ── 전처리 Step2 ───────────────────────────────────────────────────────────────

def _step2_resolve_nan(df: pd.DataFrame, conf_threshold: float = 0.3) -> pd.DataFrame:
    """visibility 기반 NaN 처리 → 3σ 이상치 제거 → 양방향 보간"""
    df    = df.copy()
    kp_x  = sorted([c for c in df.columns if c.endswith('_x')])
    kp_y  = sorted([c for c in df.columns if c.endswith('_y')])
    kp_z  = sorted([c for c in df.columns if c.endswith('_z')])
    confs = sorted([c for c in df.columns if c.endswith('_visibility')])

    n_frames, n_joints = len(df), len(kp_x)
    kp   = np.zeros((n_frames, n_joints, 3))
    conf = np.zeros((n_frames, n_joints))
    for j in range(n_joints):
        kp[:, j, 0] = df[kp_x[j]]
        kp[:, j, 1] = df[kp_y[j]]
        kp[:, j, 2] = df[kp_z[j]]
        conf[:, j]  = df[confs[j]]

    kp[conf < conf_threshold] = np.nan

    mean    = np.nanmean(kp, axis=(0, 1))
    std     = np.nanstd(kp, axis=(0, 1))
    outlier = (kp < mean - 3 * std) | (kp > mean + 3 * std)
    kp[outlier] = np.nan

    for f in range(n_frames):
        for j in range(n_joints):
            if np.isnan(kp[f, j, 0]):
                prev_val, next_val = None, None
                for p in range(f - 1, -1, -1):
                    if not np.isnan(kp[p, j, 0]):
                        prev_val = kp[p, j, :]; break
                for q in range(f + 1, n_frames):
                    if not np.isnan(kp[q, j, 0]):
                        next_val = kp[q, j, :]; break
                if prev_val is not None and next_val is not None:
                    kp[f, j, :] = (prev_val + next_val) / 2
                elif prev_val is not None:
                    kp[f, j, :] = prev_val
                elif next_val is not None:
                    kp[f, j, :] = next_val

    for j in range(n_joints):
        df[kp_x[j]] = kp[:, j, 0]
        df[kp_y[j]] = kp[:, j, 1]
        df[kp_z[j]] = kp[:, j, 2]

    # 결측 보간 — cubic 은 유효 표본 4개 이상에서만 안전하고, 표본이 부족하면
    # scipy 가 "derivatives at boundaries" 예외를 던진다. 표본 수에 따라 linear 로
    # 강등하고, 그래도 실패하면 안전하게 fallback 한다.
    num_cols = df.select_dtypes(include='number').columns
    for c in num_cols:
        s = df[c]
        valid = int(s.notna().sum())
        if valid >= 4:
            try:
                s = s.interpolate(method='cubic', limit_direction='both')
            except Exception:
                s = s.interpolate(method='linear', limit_direction='both')
        elif valid >= 2:
            s = s.interpolate(method='linear', limit_direction='both')
        df[c] = s.ffill().bfill()

    # 한 관절이 윈도우 내내 안 보이면(열 전체 NaN) 위 보간으로도 못 채워 잔여 NaN 이 남고,
    # 이후 savgol·각도계산·scaler 가 "array must not contain infs or NaNs" 로 죽는다.
    # → 윈도우를 통째로 버려 낙상 감지가 눈머는 것보다, 중립값(0)으로 채워 추론을 지속한다.
    #   (해당 관절은 정지 상태가 되어 각속도·각가속도가 0 → 오탐을 유발하지 않는다)
    df[num_cols] = df[num_cols].replace([np.inf, -np.inf], np.nan).fillna(0.0)
    return df


# ── 전처리 Step3 ───────────────────────────────────────────────────────────────

def _step3_smoothing_savgol(df: pd.DataFrame, window: int = 7, polyorder: int = 2) -> pd.DataFrame:
    """윈도우 전체에 Savitzky-Golay 스무딩 적용"""
    df         = df.copy()
    coord_cols = [c for c in df.columns if c.endswith(('_x', '_y', '_z'))]
    for col in coord_cols:
        arr = df[col].to_numpy()
        if len(arr) >= window:
            df[col] = savgol_filter(arr, window_length=window, polyorder=polyorder, mode='interp')
    return df


# ── 전처리 Step4 ───────────────────────────────────────────────────────────────

def _step4_pose_normalize(df: pd.DataFrame, pelvis: tuple = (23, 24)) -> pd.DataFrame:
    """골반 중앙정렬 + 두 골반 간 거리로 정규화"""
    df  = df.copy()
    px  = (df[f'kp{pelvis[0]}_x'] + df[f'kp{pelvis[1]}_x']) / 2
    py  = (df[f'kp{pelvis[0]}_y'] + df[f'kp{pelvis[1]}_y']) / 2
    pz  = (df[f'kp{pelvis[0]}_z'] + df[f'kp{pelvis[1]}_z']) / 2

    kp_x = [c for c in df.columns if c.endswith('_x')]
    kp_y = [c for c in df.columns if c.endswith('_y')]
    kp_z = [c for c in df.columns if c.endswith('_z')]
    for cx, cy, cz in zip(kp_x, kp_y, kp_z):
        df[cx] -= px
        df[cy] -= py
        df[cz] -= pz

    lx = df[f'kp{pelvis[0]}_x']; ly = df[f'kp{pelvis[0]}_y']; lz = df[f'kp{pelvis[0]}_z']
    rx = df[f'kp{pelvis[1]}_x']; ry = df[f'kp{pelvis[1]}_y']; rz = df[f'kp{pelvis[1]}_z']
    scale = np.sqrt((lx - rx) ** 2 + (ly - ry) ** 2 + (lz - rz) ** 2).replace(0, 1)
    for cx, cy, cz in zip(kp_x, kp_y, kp_z):
        df[cx] /= scale
        df[cy] /= scale
        df[cz] /= scale
    return df


# ── 전처리 Step5 헬퍼 ──────────────────────────────────────────────────────────

def _compute_dt(timestamps: np.ndarray) -> np.ndarray:
    n  = len(timestamps)
    dt = np.zeros_like(timestamps, dtype=float)
    if n >= 3:
        dt[1:-1] = (timestamps[2:] - timestamps[:-2]) / 2.0
    if n >= 2:
        dt[0]  = timestamps[1] - timestamps[0]
        dt[-1] = timestamps[-1] - timestamps[-2]
    return np.where(dt == 0, 1e-6, dt)


def _calc_angle(a_idx: int, b_idx: int, c_idx: int, df: pd.DataFrame) -> np.ndarray:
    """arctan2 기반 관절 각도 계산 (arccos 대비 수치 안정적)"""
    a  = df[[f'kp{a_idx}_x', f'kp{a_idx}_y', f'kp{a_idx}_z']].values
    b  = df[[f'kp{b_idx}_x', f'kp{b_idx}_y', f'kp{b_idx}_z']].values
    c  = df[[f'kp{c_idx}_x', f'kp{c_idx}_y', f'kp{c_idx}_z']].values
    ba = a - b; bc = c - b
    dot   = np.einsum('ij,ij->i', ba, bc)
    cross = np.linalg.norm(np.cross(ba, bc), axis=1)
    eps   = 1e-6
    dot   = np.where(np.abs(dot)   < eps, eps, dot)
    cross = np.where(np.abs(cross) < eps, eps, cross)
    return np.degrees(np.arctan2(cross, dot))


def _central_diff(series: np.ndarray, dt: np.ndarray, to_radian: bool = False) -> np.ndarray:
    """실제 timestamp 기반 중앙차분"""
    x   = np.radians(series) if to_radian else series.astype(float)
    out = np.zeros_like(x)
    if len(x) > 2:
        out[1:-1] = (x[2:] - x[:-2]) / (2 * dt[1:-1])
    out = np.nan_to_num(out, nan=0.0, posinf=0.0, neginf=0.0)
    return np.degrees(out) if to_radian else out


# ── 전처리 Step5 ───────────────────────────────────────────────────────────────

def _step5_make_features(df: pd.DataFrame) -> pd.DataFrame:
    """각도/각속도/각가속도(15관절) + center_distance/center_speed → 47피처"""
    df = df.copy()
    dt = _compute_dt(df['timestamp'].values)

    for name, a, b, c in _JOINT_TRIPLETS:
        angle = _calc_angle(a, b, c, df)
        omega = _central_diff(angle, dt, to_radian=True)
        alpha = _central_diff(omega,  dt, to_radian=False)
        df[f'{name}_angle']                = angle
        df[f'{name}_angular_velocity']     = omega
        df[f'{name}_angular_acceleration'] = alpha

    coords = (
        df[['kp23_x', 'kp23_y', 'kp23_z']].values
        + df[['kp24_x', 'kp24_y', 'kp24_z']].values
    ) / 2
    diff = np.diff(coords, axis=0, prepend=coords[:1])
    df['center_distance'] = np.linalg.norm(diff, axis=1)

    ts        = df['timestamp'].values
    dt_simple = np.diff(ts, prepend=ts[0])
    dt_simple = np.where(dt_simple == 0, 1e-6, dt_simple)
    df['center_speed'] = df['center_distance'] / dt_simple
    return df


# ── 전처리 Step6 ───────────────────────────────────────────────────────────────

def _step6_scale(df: pd.DataFrame) -> np.ndarray:
    """47개 고정 FEATURE_COLUMNS 순서로 StandardScaler.transform"""
    X = df[FEATURE_COLUMNS].copy()
    X = X.replace([np.inf, -np.inf], 0.0).fillna(0.0)
    return _scaler.transform(X.values)


# ── 핵심 추론 함수 (동기, 스레드 풀에서 실행) ──────────────────────────────────

def classify_level(score: float) -> str:
    """score → 정상/주의/위험. service.py도 sticky score 재분류에 이 함수를 재사용한다."""
    if score > CRITICAL_THRESHOLD:
        return "위험"
    if score > WARNING_THRESHOLD:
        return "주의"
    return "정상"


def _no_inference(status: str, level: str | None = "정상") -> dict:
    """추론 결과가 없는 경우(윈도우 미달·STRIDE 미달·landmark 부족·예외)의 공통 반환.
    status: "warming" / "skip" / "error". error 는 실패를 "정상"으로 단정하지 않도록 level=None."""
    return {"score": 0.0, "fall": False, "level": level, "features": {}, "status": status}


def infer_landmarks(landmarks: list, device_id: str, timestamp: float) -> dict:
    """
    landmark JSON → 30프레임 윈도우 → XGBoost → 2초 구간 평활화
    → {"score": float, "fall": bool, "level": str|None, "features": dict, "status": str}
    status: "ok"(추론 성공) / "warming"(윈도우 미달) / "skip"(STRIDE 미달·landmark 부족)
            / "error"(전처리·추론 예외 — level=None, 상위에서 직전값 유지)

    반환 score는 30프레임(~1초) 윈도우 평균(instant_score)을 다시
    SCORE_SMOOTH_SECONDS(2초) 구간으로 평활화한 값이다 (2026-08-05 2.5초→2초 보정).
    """
    if len(landmarks) != 33:
        return _no_inference("skip")

    # ── landmark JSON → row dict (main.py build_row() 대응) ───────────────
    raw: dict = {}
    for i, lm in enumerate(landmarks):
        raw[f"kp{i}_x"]          = lm["x"]
        raw[f"kp{i}_y"]          = lm["y"]
        raw[f"kp{i}_z"]          = lm["z"]
        raw[f"kp{i}_visibility"]  = lm["v"]
    raw["timestamp"] = timestamp  # Android 기기 시간 사용

    # ── 윈도우 버퍼 관리 (Method A — deque 단일 책임) ─────────────────────
    buf = _get_buffer(device_id)
    buf.append(raw)
    _frame_counts[device_id] = _frame_counts.get(device_id, 0) + 1

    if len(buf) < WINDOW_SIZE:
        return _no_inference("warming")
    if _frame_counts[device_id] % STRIDE != 0:
        return _no_inference("skip")

    # ── 전처리 Step2~6 + XGBoost 추론 ─────────────────────────────────────
    try:
        df_win = pd.DataFrame(buf)
        df_win = _step2_resolve_nan(df_win)
        df_win = _step3_smoothing_savgol(df_win)
        df_win = _step4_pose_normalize(df_win)
        df_win = _step5_make_features(df_win)
        X      = _step6_scale(df_win)

        proba = _model.predict_proba(X)                     # shape (30, 2)
        instant_score = float(proba[:, 1].mean() * 100)     # 30프레임(~1초) 평균
        score = _smooth_score(device_id, timestamp, instant_score)  # 2초 구간 추가 평활화
        fall  = bool(score > CRITICAL_THRESHOLD)
        level = classify_level(score)
        feats = df_win[FEATURE_COLUMNS].iloc[-1].to_dict()
        return {"score": score, "fall": fall, "level": level, "features": feats, "status": "ok"}
    except Exception as e:
        logger.error("추론 오류 device_id=%s: %s", device_id, e, exc_info=True)
        # 실패를 "정상"으로 단정하지 않는다 — 상위(process_frame)가 status="error"를 보고 직전값을 유지한다.
        return _no_inference("error", level=None)


async def infer_landmarks_async(landmarks: list, device_id: str, timestamp: float) -> dict:
    loop = asyncio.get_event_loop()
    return await loop.run_in_executor(None, infer_landmarks, landmarks, device_id, timestamp)
