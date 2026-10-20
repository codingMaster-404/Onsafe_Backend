import logging
import math

import pytest

from app.ai import engine

DEVICE = "d1"


@pytest.fixture(autouse=True)
def clean_device():
    engine._drop_device_state(DEVICE)
    yield
    engine._drop_device_state(DEVICE)


def landmarks(**override):
    """정상 landmark 33개. override로 첫 관절의 값을 바꾼다(값이 None이면 키를 뺀다)."""
    points = [{"x": 0.5, "y": 0.5, "z": 0.0, "v": 0.9} for _ in range(33)]
    for key, value in override.items():
        if value is None:
            del points[0][key]
        else:
            points[0][key] = value
    return points


def infer(points, timestamp):
    # 30프레임 미만에서만 쓰므로 모델 추론까지 가지 않는다
    return engine.infer_landmarks(points, DEVICE, timestamp)


def buffer_len():
    return len(engine._frame_buffers.get(DEVICE, ()))


def frame_count():
    return engine._frame_counts.get(DEVICE, 0)


# ── 입력 형식 검증 (D8·S8) ────────────────────────────────────

@pytest.mark.parametrize("points", [
    landmarks(v=None),
    landmarks(x="0.5"),
    landmarks(y=True),
    landmarks(z=math.nan),
    landmarks(x=math.inf),
    landmarks()[:32],
    [["0.5", "0.5", "0", "0.9"]] * 33,
    "not-a-list",
], ids=["missing_key", "string", "bool", "nan", "inf", "32_points", "not_dict", "not_list"])
def test_invalid_landmarks_are_skipped_without_touching_buffer(points):
    infer(landmarks(), 0.0)

    result = infer(points, 0.1)

    assert result["status"] == "skip"
    assert buffer_len() == 1
    assert frame_count() == 1


@pytest.mark.parametrize("timestamp", ["0.1", None, math.nan, True], ids=["string", "none", "nan", "bool"])
def test_invalid_timestamp_is_skipped(timestamp):
    result = infer(landmarks(), timestamp)

    assert result["status"] == "skip"
    assert buffer_len() == 0


def test_valid_frames_accumulate_including_out_of_range_coordinates():
    infer(landmarks(), 0.0)
    infer(landmarks(x=-0.2, y=1.3), 0.033)  # 화면 밖 관절은 정상 입력
    result = infer(landmarks(x=1), 0.066)   # 정수 좌표도 숫자로 받는다

    assert result["status"] == "warming"
    assert buffer_len() == 3
    assert frame_count() == 3


# ── timestamp 역행·중복 (D10·S9) ─────────────────────────────

def test_timestamp_regression_resets_device_state():
    for i in range(5):
        infer(landmarks(), 10.0 + i * 0.033)
    engine._score_history[DEVICE] = engine.deque([(10.0, 50.0)])

    result = infer(landmarks(), 0.0)

    assert result["status"] == "warming"
    assert buffer_len() == 1
    assert engine._frame_buffers[DEVICE][0]["timestamp"] == 0.0
    assert frame_count() == 1
    assert DEVICE not in engine._score_history


def test_same_timestamp_drops_only_that_frame():
    infer(landmarks(), 0.0)
    infer(landmarks(), 0.033)

    result = infer(landmarks(x=0.9), 0.033)

    assert result["status"] == "skip"
    assert buffer_len() == 2
    assert frame_count() == 2
    assert engine._frame_buffers[DEVICE][-1]["kp0_x"] == 0.5


def test_regression_reset_keeps_device_owner():
    owner = engine.claim_device(DEVICE)
    infer(landmarks(), 5.0)

    infer(landmarks(), 1.0)

    assert engine._device_owners[DEVICE] is owner
    engine.release_device(DEVICE, owner)


# ── 로그 (S10) ───────────────────────────────────────────────

def test_invalid_frame_warning_logged_once_per_connection(caplog):
    caplog.set_level(logging.WARNING, logger=engine.__name__)

    for _ in range(3):
        infer(landmarks(v=None), 0.0)

    assert sum("잘못된 프레임" in r.getMessage() for r in caplog.records) == 1


def test_invalid_frame_warning_logged_again_after_reset(caplog):
    caplog.set_level(logging.WARNING, logger=engine.__name__)
    infer(landmarks(v=None), 0.0)
    infer(landmarks(), 5.0)
    infer(landmarks(), 1.0)  # 역행 → 초기화

    infer(landmarks(v=None), 1.1)

    messages = [r.getMessage() for r in caplog.records]
    assert sum("잘못된 프레임" in m for m in messages) == 2
    assert sum("timestamp 역행" in m and "prev=5.0 now=1.0" in m for m in messages) == 1


def test_claim_device_clears_warning_flag():
    infer(landmarks(v=None), 0.0)
    assert DEVICE in engine._invalid_input_warned

    owner = engine.claim_device(DEVICE)

    assert DEVICE not in engine._invalid_input_warned
    engine.release_device(DEVICE, owner)
