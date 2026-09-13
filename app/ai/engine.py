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

# ── 입력 커버리지 게이트 파라미터 ──────────────────────────────────────────────
# 학습 파이프라인은 "몸이 제대로 안 잡힌" 저커버리지 입력을 아예 학습에서 제외했다:
#   · 0fps(검출 실패) 클립          → CSV 미생성
#   · video_clean_delete_with_csv   → CSV/클립 비율 < 0.5 인 비디오 통째 삭제
# 그 결과 모델은 저커버리지 입력을 본 적이 없으므로, 추론 시에도 동일 기준으로
# 저커버리지 윈도우를 걸러 학습 분포 밖 입력(예: 설치 중 손만 인식)의 오탐을 막는다.
CONF_THRESHOLD             = 0.3   # visibility 임계 (Step2 결측 마스킹과 공유)
PELVIS_COVERAGE_MIN        = 0.5   # 골반(23,24)이 보이는 프레임 비율 하한 (학습 threshold=0.5 대응)
JOINT_COVERAGE_MIN         = 0.5   # 윈도우 내 핵심 관절 전반의 유효 비율(프레임×관절) 하한
MIN_VALID_FRAMES_PER_JOINT = 2     # 다리 체인 관절별 최소 유효 프레임 (보간 가능 하한)

# 정규화 앵커(골반) — 여기가 비면 Step4 골반 정규화(중앙정렬·스케일) 자체가 무의미해진다.
_PELVIS_JOINTS = (23, 24)
# 피처(_JOINT_TRIPLETS)에 실제로 쓰이는 관절만 커버리지 판정 대상으로 삼는다.
# (눈·귀·입·손가락·뒤꿈치 등은 피처에 쓰이지 않으므로 게이트 대상에서 제외)
_CORE_JOINTS = sorted({idx for _, a, b, c in _JOINT_TRIPLETS for idx in (a, b, c)})

# 다리 체인(엉덩이-무릎-발목) 좌/우. 하체가 프레임에 들어와 있는지 판정하는 데 쓴다.
#
# [운영 정책] 카메라는 전신(full-body)이 잡히는 설치가 기준이다. 다만 실제 낙상에서는
# self-occlusion(카메라 각도상 반대편 팔·다리가 몸에 가려짐)이 흔해, "모든 관절"을 요구하면
# 진짜 낙상을 통째로 놓친다(실측: 놓친 낙상 전부가 한쪽 팔/다리 미검출로 게이트 차단).
# → 그래서 "양쪽 중 최소 한쪽 다리 체인이 유효"를 하체 존재 판정 기준으로 삼는다.
#   손만/얼굴만(골반=0)·상반신만(양쪽 다리 모두 없음)은 여전히 차단되고, 한쪽 다리만
#   가린 정상 촬영은 통과한다. 가려진 쪽 소수 관절은 Step2에서 보간/0-fill 로 메운다.
_LEG_CHAINS = ((23, 25, 27), (24, 26, 28))  # (hip, knee, ankle) 좌 / 우

# ── 하강 동역학 확정 파라미터 (ON) ──────────────────────────────────────────────
# 모델이 낙상이라 해도 "중심의 급강하"가 없으면 강등하는 후처리 레이어. 하강 속도는 몸통
# 길이(어깨중점-골반중점)로 정규화한다(카메라 화각 무관).
#   · [보정] 학습 curated 클립(14k)에선 ADL이 애초에 75를 안 넘어 레이어 효과가 없었으나,
#     실배포에 가까운 raw 영상(앱과 동일한 lite+IMAGE 추출)으로 재검증하니 빠른 정상 동작의
#     오탐이 다수 존재했고, 이 레이어가 그걸 걸러낸다.
#   · V 스윕(raw 영상 60개, 0.5~1.0/0.1): FALL 감지는 26/30으로 평평, ADL 오탐만 10→5로
#     단조 감소 → V=1.0 에서 Youden·F1 최대. 오탐 15→5(67%↓), 낙상 손실은 baseline 대비 2건.
#   · 남는 오탐은 peak_vy 가 큰 "빠른 정상 동작"(모델/ROI 영역). 표본 60개 기준이라 실로그로 재보정 권장.
FALL_CONFIRM_BY_DESCENT = True   # ON — raw 영상 오탐 억제 (False 로 끄면 모델 판정 그대로)
DESCENT_SPAN_SEC        = 0.2    # 순간 하강속도 측정 구간(초)
DESCENT_VELOCITY_MIN    = 1.0    # 몸통길이/초 단위 하강속도 하한 (raw 영상 스윕 최적점)

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


# ── 입력 커버리지 게이트 ───────────────────────────────────────────────────────

def _coverage_gate(df: pd.DataFrame) -> bool:
    """윈도우가 추론 가능한 최소 커버리지를 만족하는지 검사한다.
    학습 파이프라인(0fps 클립 제외 + video_clean_delete_with_csv threshold=0.5)을
    추론 시점에 재현해, 학습 분포 밖의 저커버리지 입력을 걸러낸다.
    True = 추론 진행, False = 저커버리지로 스킵."""
    n = len(df)
    if n == 0:
        return False

    def _valid_frames(joint: int):
        col = f'kp{joint}_visibility'
        if col not in df.columns:
            return None
        return int((df[col] >= CONF_THRESHOLD).sum())

    # 1) 정규화 앵커인 골반(23,24) — 보이는 프레임 비율이 하한 이상이어야 함.
    #    골반이 부실하면 Step4 정규화가 무너져(scale=0→1, center=0) 값 전체가 왜곡된다.
    #    손만/얼굴만 인식되는 상황은 골반이 안 잡혀 여기서 걸러진다.
    for j in _PELVIS_JOINTS:
        col = f'kp{j}_visibility'
        if col not in df.columns or (df[col] >= CONF_THRESHOLD).mean() < PELVIS_COVERAGE_MIN:
            return False

    # 2) 하체가 프레임에 들어와 있어야 함 — 양쪽 중 "최소 한쪽" 다리 체인
    #    (엉덩이-무릎-발목)이 각 관절 최소 유효 프레임을 넘겨야 통과.
    #    상반신만/다리 미검출(양쪽 다리 모두 없음)은 차단, 한쪽 self-occlusion 은 통과.
    def _chain_ok(chain):
        cnts = [_valid_frames(j) for j in chain]
        return all(c is not None and c >= MIN_VALID_FRAMES_PER_JOINT for c in cnts)
    if not any(_chain_ok(chain) for chain in _LEG_CHAINS):
        return False

    # 3) 핵심 관절 전반의 유효 비율(프레임×관절 평균)이 하한 이상이어야 함.
    valid_total = 0
    for j in _CORE_JOINTS:
        c = _valid_frames(j)
        if c is None:
            return False
        valid_total += c
    if valid_total / (len(_CORE_JOINTS) * n) < JOINT_COVERAGE_MIN:
        return False

    return True


# ── 전처리 Step2 ───────────────────────────────────────────────────────────────

def _step2_resolve_nan(df: pd.DataFrame, conf_threshold: float = CONF_THRESHOLD) -> pd.DataFrame:
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

    # 잔여 NaN/inf 처리.
    # 학습 파이프라인은 0을 "결측"으로 보고 보간으로 제거하며(Interpolation의 replace(0, NaN)),
    # "완전 가려진 관절 = 0 = 정지"라는 취급은 하지 않는다. 과거 이 자리의 0-fill 은
    # 그 관절을 raw 공간에서 (0,0,0)으로 고정했는데, 이후 Step4 골반 정규화가 프레임마다
    # 다른 affine 변환이라 정규화 공간에서는 오히려 움직이는 점이 되어 합성 각속도·각가속도(=오탐)를 만들었다.
    # → 이제 피처에 실제로 쓰이는 관절(_CORE_JOINTS)은 상위 _coverage_gate 가 윈도우별로
    #   최소 유효 프레임을 보장하므로 위 보간 단계에서 실제 좌표 기반으로 모두 채워진다.
    #   여기 남는 잔여 NaN 은 피처에 쓰이지 않는 관절(눈·귀·입·손가락·뒤꿈치 등)뿐이며,
    #   savgol/scaler 크래시만 막으면 되므로 0 으로 채운다(피처 결과에 영향 없음).
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


def _center_descent_peak_velocity(buf: deque) -> float:
    """윈도우 내 중심(골반 중점)의 '하강' 속도 피크를 몸통 길이로 정규화해 반환한다.
    낙상=급강하(큰 값), 완만한 눕기/일어남·빠른 상체동작=작은 값.
    - 이미지 y는 아래로 갈수록 증가하므로 (cy[j]-cy[j-lag])>0 이 하강. 상승(일어남)은 음수라 무시.
    - 카메라 거리/화각 차이를 줄이기 위해 몸통 길이(어깨중점-골반중점)의 중앙값으로 나눈다.
    반환 단위: 몸통길이/초."""
    rows = list(buf)
    n = len(rows)
    if n < 3:
        return 0.0
    cy = np.array([(r['kp23_y'] + r['kp24_y']) / 2.0 for r in rows])
    t  = np.array([float(r['timestamp']) for r in rows])
    sh_y = np.array([(r['kp11_y'] + r['kp12_y']) / 2.0 for r in rows])
    sh_x = np.array([(r['kp11_x'] + r['kp12_x']) / 2.0 for r in rows])
    hp_y = cy
    hp_x = np.array([(r['kp23_x'] + r['kp24_x']) / 2.0 for r in rows])
    torso = np.hypot(sh_y - hp_y, sh_x - hp_x)
    scale = float(np.median(torso))
    if scale < 1e-6:
        return 0.0

    diffs = np.diff(t)
    dt_med = float(np.median(diffs)) if len(diffs) else 0.0
    lag = max(1, round(DESCENT_SPAN_SEC / dt_med)) if dt_med > 0 else 1

    peak = 0.0
    for j in range(lag, n):
        dt = t[j] - t[j - lag]
        if dt > 0:
            v = (cy[j] - cy[j - lag]) / dt / scale   # 몸통길이/초, 양수=하강
            if v > peak:
                peak = v
    return peak


def infer_landmarks(landmarks: list, device_id: str, timestamp: float) -> dict:
    """
    landmark JSON → 30프레임 윈도우 → XGBoost → 2초 구간 평활화
    → {"score": float, "fall": bool, "level": str|None, "features": dict, "status": str}
    status: "ok"(추론 성공) / "warming"(윈도우 미달) / "skip"(STRIDE 미달·landmark 부족)
            / "low_coverage"(유효 관절/골반 커버리지 미달 — 학습 분포 밖 입력이라 추론 안 함)
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

        # 입력 커버리지 게이트 — 학습이 제외했던 저커버리지 윈도우를 추론에서도 거른다.
        # (골반 앵커 소실·설치 중 손만 인식 등 학습 분포 밖 입력의 오탐 차단)
        if not _coverage_gate(df_win):
            return _no_inference("low_coverage")

        df_win = _step2_resolve_nan(df_win)
        df_win = _step3_smoothing_savgol(df_win)
        df_win = _step4_pose_normalize(df_win)
        df_win = _step5_make_features(df_win)
        X      = _step6_scale(df_win)

        proba = _model.predict_proba(X)                     # shape (30, 2)
        instant_score = float(proba[:, 1].mean() * 100)     # 30프레임(~1초) 평균
        score = _smooth_score(device_id, timestamp, instant_score)  # 2초 구간 추가 평활화
        fall  = bool(score > CRITICAL_THRESHOLD)

        # 하강 동역학 확정 — 위험 판정이라도 중심의 급강하가 없으면 오탐으로 보고 강등한다.
        # (빠른 상체동작·완만한 눕기/일어남을 억제. 급강하 시그니처가 있어야 낙상으로 확정)
        if FALL_CONFIRM_BY_DESCENT and fall:
            peak_vy = _center_descent_peak_velocity(buf)
            if peak_vy < DESCENT_VELOCITY_MIN:
                fall  = False
                score = min(score, CRITICAL_THRESHOLD)   # 위험 알림 억제 → 주의로 강등

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
