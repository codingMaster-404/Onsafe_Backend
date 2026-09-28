package com.onsafe.backend.common.security

import com.onsafe.backend.common.exception.ErrorCode
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.Date
import javax.crypto.spec.SecretKeySpec

class JwtProviderTest {

    private val secret = "test-secret-key-32-bytes-long!!!!"
    private val accessExpiry = Duration.ofHours(1).toMillis()
    private val refreshExpiry = Duration.ofDays(30).toMillis()
    private val jwtProvider = JwtProvider(secret, accessExpiry, refreshExpiry)

    private fun payloadJson(token: String): String =
        String(Base64.getUrlDecoder().decode(token.split(".")[1]))

    // parse()는 검증 결과와 클레임을 함께 돌려주므로(C7 ③) 테스트도 두 갈래로 나눠 본다.
    private fun errorOf(token: String, type: TokenType, provider: JwtProvider = jwtProvider): ErrorCode? =
        (provider.parse(token, type) as? TokenParseResult.Invalid)?.errorCode

    private fun validOf(token: String, type: TokenType): TokenParseResult.Valid =
        jwtProvider.parse(token, type) as TokenParseResult.Valid

    // ── 토큰 타입 구분 (A1) ───────────────────────────────────────

    @Test
    fun `access 토큰은 ACCESS로만 통과하고 REFRESH 검증에서는 INVALID_TOKEN`() {
        val token = jwtProvider.generateAccessToken("testUser", Instant.now())

        assertNull(errorOf(token, TokenType.ACCESS))
        assertEquals(ErrorCode.INVALID_TOKEN, errorOf(token, TokenType.REFRESH))
    }

    @Test
    fun `refresh 토큰은 REFRESH로만 통과하고 ACCESS 검증에서는 INVALID_TOKEN`() {
        val token = jwtProvider.generateRefreshToken("testUser", Instant.now())

        assertNull(errorOf(token, TokenType.REFRESH))
        assertEquals(ErrorCode.INVALID_TOKEN, errorOf(token, TokenType.ACCESS))
    }

    @Test
    fun `typ 클레임이 없는 옛 토큰은 두 타입 모두 INVALID_TOKEN`() {
        val legacy = Jwts.builder()
            .subject("test@example.com")
            .claim("userId", "testUser")
            .issuedAt(Date())
            .expiration(Date(System.currentTimeMillis() + accessExpiry))
            .signWith(Keys.hmacShaKeyFor(secret.toByteArray()), Jwts.SIG.HS256)
            .compact()

        assertEquals(ErrorCode.INVALID_TOKEN, errorOf(legacy, TokenType.ACCESS))
        assertEquals(ErrorCode.INVALID_TOKEN, errorOf(legacy, TokenType.REFRESH))
    }

    @Test
    fun `auth_time이 없는 refresh 타입 토큰은 INVALID_TOKEN`() {
        val noAuthTime = Jwts.builder()
            .subject("testUser")
            .claim("typ", "refresh")
            .issuedAt(Date())
            .expiration(Date(System.currentTimeMillis() + refreshExpiry))
            .signWith(Keys.hmacShaKeyFor(secret.toByteArray()), Jwts.SIG.HS256)
            .compact()

        assertEquals(ErrorCode.INVALID_TOKEN, errorOf(noAuthTime, TokenType.REFRESH))
    }

    @Test
    fun `다른 시크릿으로 서명한 토큰은 INVALID_TOKEN`() {
        val other = JwtProvider("another-secret-key-32-bytes-long!!", accessExpiry, refreshExpiry)
        val token = other.generateAccessToken("testUser", Instant.now())

        assertEquals(ErrorCode.INVALID_TOKEN, errorOf(token, TokenType.ACCESS))
    }

    // ── 클레임 구조 (C4) ──────────────────────────────────────────

    @Test
    fun `subject는 userId이고 이메일·userId 클레임을 담지 않는다`() {
        val token = jwtProvider.generateAccessToken("testUser", Instant.now())
        val payload = payloadJson(token)

        assertEquals("testUser", validOf(token, TokenType.ACCESS).userId)
        assertTrue(payload.contains("\"sub\":\"testUser\""))
        assertTrue(payload.contains("\"typ\":\"access\""))
        assertFalse(payload.contains("userId"))
        assertFalse(payload.contains("@"))
    }

    // ── 만료 ─────────────────────────────────────────────────────

    @Test
    fun `만료된 access 토큰은 EXPIRED_TOKEN`() {
        val expiredProvider = JwtProvider(secret, -1000, refreshExpiry)
        val token = expiredProvider.generateAccessToken("testUser", Instant.now())

        assertEquals(ErrorCode.EXPIRED_TOKEN, errorOf(token, TokenType.ACCESS))
    }

    @Test
    fun `만료된 토큰이라도 타입이 다르면 EXPIRED가 아니라 INVALID_TOKEN`() {
        val expiredRefresh = jwtProvider.generateRefreshToken("testUser", Instant.now().minus(31, ChronoUnit.DAYS))

        assertEquals(ErrorCode.EXPIRED_TOKEN, errorOf(expiredRefresh, TokenType.REFRESH))
        assertEquals(ErrorCode.INVALID_TOKEN, errorOf(expiredRefresh, TokenType.ACCESS))
    }

    @Test
    fun `HS256 외 알고리즘 토큰은 만료 전후 모두 INVALID_TOKEN`() {
        // HS384는 48바이트 이상 키가 필요해 긴 시크릿으로만 재현된다.
        val longSecret = "0123456789abcdef0123456789abcdef0123456789abcdef01"
        val provider = JwtProvider(longSecret, accessExpiry, refreshExpiry)
        val key384 = SecretKeySpec(longSecret.toByteArray(), "HmacSHA384")
        val now = System.currentTimeMillis()
        fun hs384(exp: Long) = Jwts.builder()
            .subject("testUser")
            .claim("typ", "access")
            .issuedAt(Date(now - accessExpiry * 2))
            .expiration(Date(exp))
            .signWith(key384, Jwts.SIG.HS384)
            .compact()

        assertEquals(ErrorCode.INVALID_TOKEN, errorOf(hs384(now + accessExpiry), TokenType.ACCESS, provider))
        assertEquals(ErrorCode.INVALID_TOKEN, errorOf(hs384(now - accessExpiry), TokenType.ACCESS, provider))
    }

    // ── auth_time 기준 절대 만료 (D4·D5) ──────────────────────────

    @Test
    fun `refresh 토큰은 auth_time을 담고 만료는 auth_time + 30일로 고정된다`() {
        val authTime = Instant.now().minus(10, ChronoUnit.DAYS)
        val token = jwtProvider.generateRefreshToken("testUser", authTime)

        assertEquals(authTime.truncatedTo(ChronoUnit.SECONDS), validOf(token, TokenType.REFRESH).authTime)
        // 발급 시점과 무관하게 로그인 기준 남은 기간(약 20일)만 유효하다.
        val remaining = jwtProvider.getRemainingExpiry(token)
        assertTrue(remaining <= Duration.ofDays(20), "remaining=$remaining")
        assertTrue(remaining > Duration.ofDays(20).minusMinutes(1), "remaining=$remaining")
    }

    @Test
    fun `재발급 토큰이 auth_time을 이어받으면 첫 토큰과 만료 시각이 같다`() {
        val authTime = Instant.now().minus(5, ChronoUnit.DAYS)
        val first = jwtProvider.generateRefreshToken("testUser", authTime)
        val reissued = jwtProvider.generateRefreshToken("testUser", validOf(first, TokenType.REFRESH).authTime!!)

        val diff = jwtProvider.getRemainingExpiry(first) - jwtProvider.getRemainingExpiry(reissued)
        assertTrue(diff.abs() < Duration.ofSeconds(1), "diff=$diff")
    }

    @Test
    fun `access 토큰은 평소 발급 후 1시간에 만료된다`() {
        val token = jwtProvider.generateAccessToken("testUser", Instant.now())

        val remaining = jwtProvider.getRemainingExpiry(token)
        assertTrue(remaining <= Duration.ofHours(1), "remaining=$remaining")
        assertTrue(remaining > Duration.ofHours(1).minusMinutes(1), "remaining=$remaining")
    }

    @Test
    fun `로그인 30일 직전에 발급된 access 토큰은 auth_time + 30일에 만료된다 (D7)`() {
        // 로그인 후 29일 23시간 50분 경과 — 1시간 대신 남은 10분만 유효해야 한다.
        val authTime = Instant.now().minus(30, ChronoUnit.DAYS).plus(10, ChronoUnit.MINUTES)
        val token = jwtProvider.generateAccessToken("testUser", authTime)

        val remaining = jwtProvider.getRemainingExpiry(token)
        assertTrue(remaining <= Duration.ofMinutes(10), "remaining=$remaining")
        assertTrue(remaining > Duration.ofMinutes(9), "remaining=$remaining")
    }

    @Test
    fun `로그인 30일이 지난 auth_time으로 발급한 access 토큰은 발급 즉시 EXPIRED_TOKEN (D7)`() {
        val token = jwtProvider.generateAccessToken("testUser", Instant.now().minus(31, ChronoUnit.DAYS))

        assertEquals(ErrorCode.EXPIRED_TOKEN, errorOf(token, TokenType.ACCESS))
    }

    // ── iat (A2 무효화 판정 기준) ─────────────────────────────────

    @Test
    fun `parse는 발급 시각(iat)을 초 단위로 돌려준다`() {
        val before = Instant.now().truncatedTo(ChronoUnit.SECONDS)
        val token = jwtProvider.generateAccessToken("testUser", Instant.now())
        val after = Instant.now()

        val issuedAt = validOf(token, TokenType.ACCESS).issuedAt
        assertTrue(!issuedAt.isBefore(before) && !issuedAt.isAfter(after), "issuedAt=$issuedAt")
        assertEquals(0, issuedAt.nano)
    }

    @Test
    fun `iat가 없는 토큰은 INVALID_TOKEN`() {
        val noIat = Jwts.builder()
            .subject("testUser")
            .claim("typ", "access")
            .expiration(Date(System.currentTimeMillis() + accessExpiry))
            .signWith(Keys.hmacShaKeyFor(secret.toByteArray()), Jwts.SIG.HS256)
            .compact()

        assertEquals(ErrorCode.INVALID_TOKEN, errorOf(noIat, TokenType.ACCESS))
    }

    // ── parse 결과 (C7 ③) ────────────────────────────────────────

    @Test
    fun `parse는 access 토큰의 userId·iat·남은 만료를 한 번에 돌려주고 authTime은 null`() {
        val token = jwtProvider.generateAccessToken("testUser", Instant.now())

        val parsed = validOf(token, TokenType.ACCESS)
        assertEquals("testUser", parsed.userId)
        assertNull(parsed.authTime) // access에는 auth_time을 넣지 않는다
        assertEquals(jwtProvider.getRemainingExpiry(token).toMinutes(), parsed.remainingExpiry.toMinutes())
    }

    @Test
    fun `parse는 refresh 토큰의 authTime을 돌려준다`() {
        val authTime = Instant.now().minus(3, ChronoUnit.DAYS)
        val token = jwtProvider.generateRefreshToken("testUser", authTime)

        assertEquals(authTime.truncatedTo(ChronoUnit.SECONDS), validOf(token, TokenType.REFRESH).authTime)
    }

    // ── 시크릿 검증 (D1) ──────────────────────────────────────────

    @Test
    fun `시크릿이 32바이트 미만이면 생성 시점에 실패한다`() {
        assertThrows<IllegalArgumentException> { JwtProvider("short-secret", accessExpiry, refreshExpiry) }
        assertThrows<IllegalArgumentException> { JwtProvider("", accessExpiry, refreshExpiry) }
    }

    @Test
    fun `재동의 필요로 발급한 access 토큰만 consentRequired가 true이고, 평소 토큰에는 cr 클레임이 없다`() {
        val blocked = jwtProvider.generateAccessToken("testUser", Instant.now(), consentRequired = true)
        val normal = jwtProvider.generateAccessToken("testUser", Instant.now())

        assertTrue(validOf(blocked, TokenType.ACCESS).consentRequired)
        assertFalse(validOf(normal, TokenType.ACCESS).consentRequired)
        assertFalse(payloadJson(normal).contains("\"cr\""))
    }
}
