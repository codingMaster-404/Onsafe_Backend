"""
Redis 기반 AI 추론 지원 버퍼

Redis 키 역할:
  score:{user_id}        TTL 30s    최신 위험 점수 캐시 (추론 실패 시 직전 점수 폴백용)
  caution_cd:{user_id}   TTL 300s   WARNING 중복 방지 쿨다운 (저장 실패 시 shorten_*로 30초로 줄임)
  danger_cd:{user_id}    TTL 21600s CRITICAL(위험)·fall 이벤트 중복 방지 쿨다운 (6시간, 저장 실패 시 shorten_*로 30초로 줄임)
  score_floor:{user_id}  TTL 900s   CRITICAL 진입 시 점수 하락 방지용 최고값 유지 (15분, sticky)
  stream:alive:{user_id} TTL 90s    WS 프레임 수신 생존 신호 — Kotlin HeartbeatWatchdogJob이 "감지 중단" 판정에 사용
                                    (키 이름·TTL은 Kotlin StreamAliveStore와 함께 바꿔야 한다)

프레임 카운터 / 추론 시작 판단은 engine.py의 _frame_buffers deque가 담당한다 (Method A).
"""
import json
from app.core.redis import get_redis

CAUTION_COOLDOWN_SECONDS = 300
DANGER_COOLDOWN_SECONDS = 21600
# 이벤트 저장이 실패했을 때 다시 시도하기까지의 간격. 추론이 초당 약 6회라 바로 풀면
# Kotlin 장애 중 추론마다 호출이 반복되고, Kotlin이 응답하지 않으면 매번 타임아웃만큼 스트림이 멈춘다.
SAVE_RETRY_SECONDS = 30


async def save_score(user_id: str, score: float, level: str) -> None:
    r = get_redis()
    await r.set(
        f"score:{user_id}",
        json.dumps({"score": score, "level": level}),
        ex=30,
    )


async def get_score(user_id: str) -> dict | None:
    r = get_redis()
    val = await r.get(f"score:{user_id}")
    return json.loads(val) if val else None


async def check_caution_cooldown(user_id: str, ttl_seconds: int = CAUTION_COOLDOWN_SECONDS) -> bool:
    """WARNING 이벤트 쿨다운 확인.
    True = 쿨다운 없음(이벤트 저장 가능), False = 쿨다운 중.
    Redis NX 플래그로 원자적 설정 — 중복 알림/로그 방지 (기본 5분)."""
    r = get_redis()
    key = f"caution_cd:{user_id}"
    result = await r.set(key, "1", ex=ttl_seconds, nx=True)
    return result is not None


async def check_danger_cooldown(user_id: str, ttl_seconds: int = DANGER_COOLDOWN_SECONDS) -> bool:
    """CRITICAL(위험)·fall 이벤트 쿨다운 확인 (기본 6시간).
    True = 쿨다운 없음(이벤트 저장 가능), False = 쿨다운 중.
    한 번 위험을 알리면 보호자가 계속 주시할 것이라는 전제로 알림 피로도를 낮춘다.
    score>CRITICAL_THRESHOLD와 fall=True 모두 이 쿨다운을 공유한다(스펙 확정, 2026-07-13)."""
    r = get_redis()
    key = f"danger_cd:{user_id}"
    result = await r.set(key, "1", ex=ttl_seconds, nx=True)
    return result is not None


async def shorten_caution_cooldown(user_id: str, retry_after_seconds: int = SAVE_RETRY_SECONDS) -> None:
    """check_caution_cooldown으로 잡은 쿨다운을 retry_after_seconds로 줄인다 — 이벤트 저장이 실패했을 때
    그 뒤 추론이 다시 저장하도록. 다른 기기가 그 사이 잡은 키여도 그대로 덮어쓴다(알림 1회 추가는 누락보다 낫다).
    Redis 오류는 호출부(camera/service.py)에서 처리하도록 그대로 올린다."""
    r = get_redis()
    await r.set(f"caution_cd:{user_id}", "1", ex=retry_after_seconds)


async def shorten_danger_cooldown(user_id: str, retry_after_seconds: int = SAVE_RETRY_SECONDS) -> None:
    """check_danger_cooldown으로 잡은 쿨다운을 retry_after_seconds로 줄인다 — 이벤트 저장이 실패했을 때
    그 뒤 추론이 다시 저장하도록. 다른 기기가 그 사이 잡은 키여도 그대로 덮어쓴다(알림 1회 추가는 누락보다 낫다).
    Redis 오류는 호출부(camera/service.py)에서 처리하도록 그대로 올린다."""
    r = get_redis()
    await r.set(f"danger_cd:{user_id}", "1", ex=retry_after_seconds)


async def apply_score_floor(user_id: str, score: float, danger_threshold: float, ttl_seconds: int = 900) -> float:
    """CRITICAL(위험) 진입 시 점수가 그 이하로 떨어지지 않도록 15분간 유지한다.
    - score가 danger_threshold를 넘으면: 기존 최고값과 비교해 더 큰 값으로 갱신, TTL 갱신(지속되는 위험 상태를 계속 연장)
    - score가 danger_threshold 이하이지만 유지 중인 최고값이 남아있으면: 그 값을 그대로 반환(하락 방지), TTL은 그대로 흘러가게 둠
    - 유지 중인 값이 없으면: 원본 score 그대로 반환
    반환값은 실제로 화면·알림 판단에 사용할 effective score."""
    r = get_redis()
    key = f"score_floor:{user_id}"
    raw = await r.get(key)
    floor = float(raw) if raw is not None else None

    if score > danger_threshold:
        new_floor = max(floor, score) if floor is not None else score
        await r.set(key, new_floor, ex=ttl_seconds)
        return new_floor

    return floor if floor is not None else score


STREAM_ALIVE_TTL_SECONDS = 90


async def mark_stream_alive(user_id: str, ttl_seconds: int = STREAM_ALIVE_TTL_SECONDS) -> None:
    """WS로 프레임이 들어오고 있다는 생존 신호를 남긴다(카메라 스트리밍 B5).
    앱 heartbeat는 카메라 화면이 열려 있으면 WS와 무관하게 오므로, 실제 프레임 수신은 이 키로만 알 수 있다.
    연결이 끊겨도 지우지 않고 TTL로 사라지게 둔다 — 같은 사용자의 다른 연결이 살아 있을 수 있다."""
    r = get_redis()
    await r.set(f"stream:alive:{user_id}", "1", ex=ttl_seconds)
