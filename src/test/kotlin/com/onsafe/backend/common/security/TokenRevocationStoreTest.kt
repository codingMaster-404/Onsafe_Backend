package com.onsafe.backend.common.security

import com.onsafe.backend.common.exception.BusinessException
import com.onsafe.backend.common.exception.ErrorCode
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.data.redis.core.ReactiveValueOperations
import reactor.core.publisher.Mono
import java.time.Duration
import java.time.Instant

class TokenRevocationStoreTest {

    private val redis: ReactiveStringRedisTemplate = mockk()
    private val valueOps: ReactiveValueOperations<String, String> = mockk()
    private val refreshExpiry = Duration.ofDays(30).toMillis()
    private val store = TokenRevocationStore(redis, refreshExpiry).also {
        every { redis.opsForValue() } returns valueOps
    }

    // ── 판정 경계 (D9: iat <= 무효화 시각이면 거부) ─────────────────

    @Test
    fun `무효화 시각보다 이전에 발급된 토큰은 무효`() {
        assertTrue(store.isRevoked(Instant.ofEpochSecond(999), "1000"))
    }

    @Test
    fun `무효화와 같은 초에 발급된 토큰도 무효`() {
        assertTrue(store.isRevoked(Instant.ofEpochSecond(1000, 999_000_000), "1000"))
    }

    @Test
    fun `무효화 시각 이후에 발급된 토큰은 유효`() {
        assertFalse(store.isRevoked(Instant.ofEpochSecond(1001), "1000"))
    }

    @Test
    fun `무효화 기록이 없으면 유효`() {
        assertFalse(store.isRevoked(Instant.ofEpochSecond(1000), null))
    }

    @Test
    fun `저장값이 숫자가 아니면 무효로 본다 (fail-closed)`() {
        assertTrue(store.isRevoked(Instant.ofEpochSecond(2000), "broken"))
    }

    // ── 저장·조회 ────────────────────────────────────────────────

    @Test
    fun `revokeAll은 현재 epoch 초를 refresh 유효기간 TTL로 저장한다`() = runTest {
        val value = slot<String>()
        every { valueOps.set("token_valid_after:testUser", capture(value), Duration.ofDays(30)) } returns Mono.just(true)

        val before = Instant.now().epochSecond
        store.revokeAll("testUser")
        val after = Instant.now().epochSecond

        assertTrue(value.captured.toLong() in before..after, "value=${value.captured}")
    }

    @Test
    fun `isRevoked(userId)는 저장값을 조회해 같은 규칙으로 판정한다`() = runTest {
        every { valueOps.get("token_valid_after:testUser") } returns Mono.just("1000")

        assertTrue(store.isRevoked("testUser", Instant.ofEpochSecond(1000)))
        assertFalse(store.isRevoked("testUser", Instant.ofEpochSecond(1001)))
    }

    @Test
    fun `Redis 오류는 REDIS_UNAVAILABLE로 감싼다`() = runTest {
        every { valueOps.get("token_valid_after:testUser") } returns Mono.error(RuntimeException("connection refused"))

        val thrown = runCatching { store.isRevoked("testUser", Instant.now()) }.exceptionOrNull()

        assertTrue(thrown is BusinessException)
        assertEquals(ErrorCode.REDIS_UNAVAILABLE, (thrown as BusinessException).errorCode)
    }
}
