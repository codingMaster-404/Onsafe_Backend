package com.onsafe.backend.common.security

import com.onsafe.backend.common.util.guardRedis
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactor.awaitSingle
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant

/**
 * 사용자 단위 세션 무효화 — 탈퇴·비밀번호 변경·재설정 시 그 사용자의 기존 토큰을 모두 끊는다.
 *
 * 토큰마다 블랙리스트하려면 발급한 토큰을 전부 알아야 하므로, 대신 "이 시각 이전에 발급된 토큰은
 * 무효"라는 기준 시각 하나를 `token_valid_after:{userId}`에 둔다.
 *
 * 키 이름과 판정 규칙은 반드시 이 클래스 하나로만 다룬다. 필터(Mono)·AuthService·UserService가
 * 각자 구현하면 blacklistKey처럼 규칙이 어긋나 무효화가 조용히 무력화된다.
 * Python 서비스(app/core/security.py)도 같은 키·규칙을 쓰므로 바꾸면 함께 바꿔야 한다.
 */
@Component
class TokenRevocationStore(
    private val redis: ReactiveStringRedisTemplate,
    @Value("\${jwt.refresh-token-expiry}") refreshTokenExpiry: Long
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // 무효화 시점 이전에 발급된 토큰은 길어도 발급 후 refresh 유효기간(30일) 안에 만료된다
    // (refresh는 auth_time 고정, access도 같은 상한). 그 뒤로는 키가 필요 없으므로 같은 TTL을 둔다.
    private val ttl: Duration = Duration.ofMillis(refreshTokenExpiry)

    fun key(userId: String): String = "token_valid_after:$userId"

    /** 지금 이전(같은 초 포함)에 발급된 [userId]의 모든 토큰을 무효화한다. */
    suspend fun revokeAll(userId: String) {
        log.guardRedis("세션 무효화 시각 저장") {
            redis.opsForValue().set(key(userId), Instant.now().epochSecond.toString(), ttl).awaitSingle()
        }
    }

    suspend fun isRevoked(userId: String, issuedAt: Instant): Boolean {
        val validAfter = log.guardRedis("세션 무효화 시각 조회") {
            redis.opsForValue().get(key(userId)).awaitFirstOrNull()
        }
        return isRevoked(issuedAt, validAfter)
    }

    /**
     * [validAfter]는 Redis에 저장된 값(epoch 초 문자열, 없으면 null).
     * iat와 저장값이 모두 초 단위라, 무효화와 같은 초에 발급된 토큰도 거부한다(`<=`).
     * 무효화 요청에서 새 토큰을 발급하지 않으므로(완료 문서 D6) 같은 초 거부로 잃는 정상 토큰이 없다.
     */
    fun isRevoked(issuedAt: Instant, validAfter: String?): Boolean {
        if (validAfter == null) return false
        // 이 클래스만 쓰는 키라 숫자가 아닐 수 없지만, 깨진 값이면 통과시키지 않는다(fail-closed).
        val validAfterSec = validAfter.toLongOrNull() ?: return true
        return issuedAt.epochSecond <= validAfterSec
    }
}
