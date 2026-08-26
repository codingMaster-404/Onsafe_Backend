package com.onsafe.backend.common.ratelimit

import com.onsafe.backend.common.exception.BusinessException
import com.onsafe.backend.common.exception.ErrorCode
import com.onsafe.backend.common.util.guardRedis
import kotlinx.coroutines.reactive.awaitSingle
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.data.redis.core.script.RedisScript
import org.springframework.stereotype.Component

/**
 * Redis INCR + EXPIRE 기반 고정창(fixed-window) 리미터.
 * 첫 요청 시에만 TTL을 걸어, 창이 지나면 자동으로 카운트가 초기화된다.
 */
@Component
class RateLimiter(private val redis: ReactiveStringRedisTemplate) {

    private val log = LoggerFactory.getLogger(javaClass)

    // INCR와 EXPIRE를 별개 호출로 하면 그 사이 코루틴이 취소되거나 Redis 연결이 끊겼을 때
    // 카운터에 TTL이 영영 안 걸려 해당 키가 자동 초기화 없이 계속 누적되는 문제가 생긴다.
    // Lua 스크립트는 Redis에서 원자적으로 실행되므로 이 틈이 존재하지 않는다.
    private val incrementAndExpireScript: RedisScript<Long> = RedisScript.of(
        """
        local count = redis.call('INCR', KEYS[1])
        if count == 1 then
            redis.call('EXPIRE', KEYS[1], ARGV[1])
        end
        return count
        """.trimIndent(),
        Long::class.java
    )

    /**
     * @return true = 허용, false = 초과. Redis 장애 시 예외를 그대로 전파(fail-closed)한다 —
     * 로그인/코드 발송 같은 보안 관문에서 조용히 통과시키는 것보다 명시적 오류가 안전.
     * 호출부에서 직접 예외를 처리하지 않아도 되도록 대부분의 경우 [requireAllowed]를 쓴다.
     */
    suspend fun allow(key: String, limit: Long, windowSec: Long): Boolean {
        val count = redis.execute(incrementAndExpireScript, listOf(key), listOf(windowSec.toString())).awaitSingle()
        return count <= limit
    }

    /**
     * [allow]를 호출해 초과 시 [ErrorCode.TOO_MANY_REQUESTS]를, Redis 장애로 확인 자체가 실패하면
     * [ErrorCode.REDIS_UNAVAILABLE]을 던진다. 외부 SDK(Redis) 예외가 서비스 레이어를 거치지 않고
     * 컨트롤러까지 그대로 전파되지 않도록 여기서 BusinessException으로 래핑한다.
     */
    suspend fun requireAllowed(key: String, limit: Long, windowSec: Long) {
        val allowed = log.guardRedis("rate-limit key=$key") { allow(key, limit, windowSec) }
        if (!allowed) throw BusinessException(ErrorCode.TOO_MANY_REQUESTS)
    }
}
