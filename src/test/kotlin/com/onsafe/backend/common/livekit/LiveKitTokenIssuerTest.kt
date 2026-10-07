package com.onsafe.backend.common.livekit

import com.onsafe.backend.common.exception.BusinessException
import com.onsafe.backend.common.exception.ErrorCode
import io.jsonwebtoken.Claims
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import java.time.Instant

/**
 * LiveKit 토큰은 LiveKit 서버가 API 시크릿으로 서명을 검증하는 HS256 JWT다.
 * 역할별 권한(보호자 구독 전용·피보호자 송출 전용)·방 이름·유효시간이 의도대로 실리는지 토큰을 직접 풀어 확인한다.
 */
class LiveKitTokenIssuerTest {

    private val apiKey = "APItestkey"
    private val apiSecret = "test-livekit-secret-32-bytes-long!!"
    private val issuer = LiveKitTokenIssuer("wss://test.livekit.cloud", apiKey, apiSecret)

    private fun claimsOf(token: String): Claims =
        Jwts.parser().verifyWith(Keys.hmacShaKeyFor(apiSecret.toByteArray())).build()
            .parseSignedClaims(token).payload

    @Suppress("UNCHECKED_CAST")
    private fun videoGrant(claims: Claims): Map<String, Any> = claims["video"] as Map<String, Any>

    @Test
    fun `보호자 토큰 - 피보호자 방에 구독만 가능하고 송출·데이터 전송은 불가`() {
        val claims = claimsOf(issuer.viewerToken("elder1", "guardian1", Duration.ofMinutes(5)))
        val video = videoGrant(claims)

        assertEquals(apiKey, claims.issuer)
        assertEquals("guardian-guardian1", claims.subject)
        assertEquals("live-elder1", video["room"])
        assertEquals(true, video["roomJoin"])
        assertEquals(true, video["canSubscribe"])
        assertEquals(false, video["canPublish"])
        assertEquals(false, video["canPublishData"])
    }

    @Test
    fun `피보호자 토큰 - 자기 방에 송출만 가능하고 구독은 불가`() {
        val claims = claimsOf(issuer.publisherToken("elder1", Duration.ofMinutes(5)))
        val video = videoGrant(claims)

        assertEquals("elder-elder1", claims.subject)
        assertEquals("live-elder1", video["room"])
        assertEquals(true, video["canPublish"])
        assertEquals(false, video["canSubscribe"])
        assertEquals(false, video["canPublishData"])
    }

    @Test
    fun `유효시간은 요청한 TTL을 따른다`() {
        val before = Instant.now()
        val claims = claimsOf(issuer.viewerToken("elder1", "guardian1", Duration.ofMinutes(5)))

        val ttl = Duration.between(before, claims.expiration.toInstant())
        assertTrue(ttl <= Duration.ofMinutes(5).plusSeconds(1) && ttl >= Duration.ofMinutes(5).minusSeconds(5), "ttl=$ttl")
    }

    @Test
    fun `설정이 비어 있으면 LIVE_UNAVAILABLE`() {
        val blank = LiveKitTokenIssuer("", "", "")

        val e = assertThrows<BusinessException> { blank.viewerToken("elder1", "guardian1", Duration.ofMinutes(5)) }
        assertEquals(ErrorCode.LIVE_UNAVAILABLE, e.errorCode)
    }
}
