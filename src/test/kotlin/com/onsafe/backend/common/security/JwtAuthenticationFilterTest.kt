package com.onsafe.backend.common.security

import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.data.redis.core.ReactiveValueOperations
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

class JwtAuthenticationFilterTest {

    private val jwtProvider = JwtProvider("test-secret-key-32-bytes-long!!!!", Duration.ofHours(1).toMillis(), Duration.ofDays(30).toMillis())
    private val redis: ReactiveStringRedisTemplate = mockk()
    private val valueOps: ReactiveValueOperations<String, String> = mockk()
    private val store = TokenRevocationStore(redis, Duration.ofDays(30).toMillis())
    private val filter = JwtAuthenticationFilter(jwtProvider, redis, ObjectMapper(), store)

    init {
        every { redis.opsForValue() } returns valueOps
    }

    // 체인이 호출됐는지(= 인증 통과) 기록하는 가짜 다음 필터
    private class RecordingChain : WebFilterChain {
        var called = false
        override fun filter(exchange: org.springframework.web.server.ServerWebExchange): Mono<Void> {
            called = true
            return Mono.empty()
        }
    }

    private fun exchangeWith(token: String?, path: String = "/api/users/testUser"): MockServerWebExchange {
        val request = MockServerHttpRequest.get(path).apply {
            if (token != null) header(HttpHeaders.AUTHORIZATION, "Bearer $token")
        }.build()
        return MockServerWebExchange.from(request)
    }

    private fun stubRedis(token: String, blacklisted: String?, validAfter: String?) {
        every {
            valueOps.multiGet(listOf(jwtProvider.blacklistKey(token), "token_valid_after:testUser"))
        } returns Mono.just(listOf(blacklisted, validAfter))
    }

    private fun run(exchange: MockServerWebExchange): RecordingChain {
        val chain = RecordingChain()
        filter.filter(exchange, chain).block()
        return chain
    }

    @Test
    fun `유효한 access 토큰 + 블랙리스트·무효화 없음이면 통과`() {
        val token = jwtProvider.generateAccessToken("testUser", Instant.now())
        stubRedis(token, blacklisted = null, validAfter = null)

        val exchange = exchangeWith(token)
        val chain = run(exchange)

        assertTrue(chain.called)
        assertNull(exchange.response.statusCode)
    }

    @Test
    fun `블랙리스트된 토큰이면 401`() {
        val token = jwtProvider.generateAccessToken("testUser", Instant.now())
        stubRedis(token, blacklisted = "1", validAfter = null)

        val exchange = exchangeWith(token)
        val chain = run(exchange)

        assertFalse(chain.called)
        assertEquals(HttpStatus.UNAUTHORIZED, exchange.response.statusCode)
    }

    @Test
    fun `무효화 시각 이후 발급된 토큰이면 통과`() {
        val token = jwtProvider.generateAccessToken("testUser", Instant.now())
        val validAfter = Instant.now().minus(1, ChronoUnit.HOURS).epochSecond.toString()
        stubRedis(token, blacklisted = null, validAfter = validAfter)

        assertTrue(run(exchangeWith(token)).called)
    }

    @Test
    fun `무효화 시각 이전(같은 초 포함)에 발급된 토큰이면 401 (A2·D9)`() {
        val token = jwtProvider.generateAccessToken("testUser", Instant.now())
        val validAfter = (jwtProvider.parse(token, TokenType.ACCESS) as TokenParseResult.Valid)
            .issuedAt.epochSecond.toString() // 같은 초
        stubRedis(token, blacklisted = null, validAfter = validAfter)

        val exchange = exchangeWith(token)
        val chain = run(exchange)

        assertFalse(chain.called)
        assertEquals(HttpStatus.UNAUTHORIZED, exchange.response.statusCode)
    }

    @Test
    fun `refresh 토큰을 Authorization에 넣으면 Redis 조회 없이 401 (A1)`() {
        val token = jwtProvider.generateRefreshToken("testUser", Instant.now())

        val exchange = exchangeWith(token)
        val chain = run(exchange)

        assertFalse(chain.called)
        assertEquals(HttpStatus.UNAUTHORIZED, exchange.response.statusCode)
    }

    @Test
    fun `토큰이 없으면 인증 없이 체인으로 넘긴다 (인가는 SecurityConfig가 처리)`() {
        assertTrue(run(exchangeWith(null)).called)
    }

    // ── C7 ①② · B7 ──────────────────────────────────────────────

    @Test
    fun `Redis 장애로 토큰 상태를 확인할 수 없으면 503 (C7 ①)`() {
        val token = jwtProvider.generateAccessToken("testUser", Instant.now())
        every {
            valueOps.multiGet(listOf(jwtProvider.blacklistKey(token), "token_valid_after:testUser"))
        } returns Mono.error(RuntimeException("Redis down"))

        val exchange = exchangeWith(token)
        val chain = run(exchange)

        // 401(토큰 문제)로 내리면 앱이 재발급·강제 로그아웃으로 오인한다. 통과시키지도 않는다(fail-closed).
        assertFalse(chain.called)
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exchange.response.statusCode)
    }

    @Test
    fun `쿼리 파라미터 토큰은 더 이상 인증에 쓰이지 않는다 (C7 ②)`() {
        val token = jwtProvider.generateAccessToken("testUser", Instant.now())
        val request = MockServerHttpRequest.get("/api/users/testUser?token=$token").build()
        val exchange = MockServerWebExchange.from(request)

        val chain = run(exchange)

        // 토큰이 없는 요청과 같게 취급된다 — Redis 조회도 하지 않고(목이 없어도 통과) 인가는 SecurityConfig가 막는다.
        assertTrue(chain.called)
        assertNull(exchange.response.statusCode)
    }

    @Test
    fun `실패 응답에 에러 코드가 담긴다 (B7)`() {
        val token = jwtProvider.generateAccessToken("testUser", Instant.now())
        stubRedis(token, blacklisted = "1", validAfter = null)

        val exchange = exchangeWith(token)
        run(exchange)

        val body = exchange.response.bodyAsString.block()
        assertTrue(body!!.contains("\"code\":\"INVALID_TOKEN\""), "body=$body")
        assertTrue(body.contains("\"success\":false"), "body=$body")
    }

    @Test
    fun `공개 경로는 토큰을 검사하지 않는다`() {
        val exchange = exchangeWith("garbage", path = "/api/auth/login")
        val chain = run(exchange)

        assertTrue(chain.called)
        assertNull(exchange.response.statusCode)
    }
}
