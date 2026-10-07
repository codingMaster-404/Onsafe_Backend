package com.onsafe.backend.domain.live

import com.onsafe.backend.common.exception.BusinessException
import com.onsafe.backend.common.exception.ErrorCode
import com.onsafe.backend.common.livekit.LiveKitTokenIssuer
import com.onsafe.backend.common.ratelimit.RateLimiter
import com.onsafe.backend.domain.auth.model.entity.LoginHistory
import com.onsafe.backend.domain.auth.model.entity.SecurityEventType
import com.onsafe.backend.domain.auth.repository.LoginHistoryRepository
import com.onsafe.backend.domain.camera.model.entity.RealtimeData
import com.onsafe.backend.domain.camera.repository.RealtimeDataRepository
import com.onsafe.backend.domain.guardian.model.entity.GuardianLink
import com.onsafe.backend.domain.guardian.repository.GuardianLinkRepository
import com.onsafe.backend.domain.live.model.entity.LiveSession
import com.onsafe.backend.domain.live.repository.LiveSessionRepository
import com.onsafe.backend.domain.live.service.LiveService
import com.onsafe.backend.domain.live.service.LiveSessionTerminator
import com.onsafe.backend.domain.notification.service.NotificationService
import com.onsafe.backend.domain.settings.model.entity.UserSettings
import com.onsafe.backend.domain.settings.repository.SettingsRepository
import io.jsonwebtoken.Claims
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import java.time.Instant

/**
 * 실시간 영상 세션 — 인가(연결된 보호자·피보호자 동의), 세션 생성·연장·종료, 송출 토큰, 열람 기록.
 * 토큰은 실제 발급기로 만들어 권한(구독/송출)까지 확인한다.
 */
class LiveServiceTest {

    private val guardianLinkRepository: GuardianLinkRepository = mockk()
    private val settingsRepository: SettingsRepository = mockk()
    private val liveSessionRepository: LiveSessionRepository = mockk()
    private val rateLimiter: RateLimiter = mockk()
    private val loginHistoryRepository: LoginHistoryRepository = mockk()
    private val notificationService: NotificationService = mockk()
    private val terminator: LiveSessionTerminator = mockk()
    private val realtimeDataRepository: RealtimeDataRepository = mockk()

    private val apiSecret = "test-livekit-secret-32-bytes-long!!"
    private val tokenIssuer = LiveKitTokenIssuer("wss://test.livekit.cloud", "APItestkey", apiSecret)

    private val service = LiveService(
        guardianLinkRepository, settingsRepository, liveSessionRepository,
        tokenIssuer, rateLimiter, loginHistoryRepository, notificationService, terminator, realtimeDataRepository
    )

    private val now = Instant.parse("2026-10-07T03:00:00Z")
    private val guardian = "guardian1"
    private val elder = "elder1"

    // 기본: 피보호자 카메라 온라인(1분 전 heartbeat). 오프라인 케이스만 각 테스트에서 덮어쓴다.
    init {
        givenHeartbeat(now.minusSeconds(60))
    }

    private fun givenHeartbeat(at: Instant?) {
        coEvery { realtimeDataRepository.findByUserId(elder) } returns RealtimeData(
            userId = elder,
            lastHeartbeatAt = at?.let { java.time.LocalDateTime.ofInstant(it, java.time.ZoneId.systemDefault()) }
        )
    }

    private fun givenNewSessionSaves() {
        coEvery { liveSessionRepository.find(elder) } returns null
        coEvery { liveSessionRepository.save(any(), now) } just runs
        coEvery { loginHistoryRepository.save(any()) } coAnswers { firstArg() }
    }

    private fun givenLinked() {
        coEvery { guardianLinkRepository.find(guardian, elder) } returns GuardianLink(guardian, elder)
    }

    private fun givenLiveVideoEnabled(enabled: Boolean) {
        coEvery { settingsRepository.findByUserId(elder) } returns UserSettings(userId = elder, liveVideoEnabled = enabled)
    }

    private fun givenAllowed() {
        coEvery { rateLimiter.requireAllowed(any(), any(), any()) } just runs
        coEvery { notificationService.sendDataMessage(any(), any(), any()) } returns 1
    }

    private fun claimsOf(token: String): Claims =
        Jwts.parser().verifyWith(Keys.hmacShaKeyFor(apiSecret.toByteArray())).build()
            .parseSignedClaims(token).payload

    @Suppress("UNCHECKED_CAST")
    private fun video(claims: Claims) = claims["video"] as Map<String, Any>

    // ── 시작 ─────────────────────────────────────────────

    @Test
    fun `시작 - 연결되지 않은 보호자는 FORBIDDEN이고 세션을 만들지 않는다`() = runTest {
        coEvery { guardianLinkRepository.find(guardian, elder) } returns null

        val e = assertThrows<BusinessException> { service.startSession(guardian, elder, now) }

        assertEquals(ErrorCode.FORBIDDEN, e.errorCode)
        coVerify(exactly = 0) { liveSessionRepository.save(any(), any()) }
    }

    @Test
    fun `시작 - 자기 자신에게 요청하면 FORBIDDEN`() = runTest {
        val e = assertThrows<BusinessException> { service.startSession(elder, elder, now) }

        assertEquals(ErrorCode.FORBIDDEN, e.errorCode)
    }

    @Test
    fun `시작 - 피보호자가 영상 송출에 동의하지 않았으면 LIVE_NOT_ALLOWED`() = runTest {
        givenLinked(); givenAllowed(); givenLiveVideoEnabled(false)

        val e = assertThrows<BusinessException> { service.startSession(guardian, elder, now) }

        assertEquals(ErrorCode.LIVE_NOT_ALLOWED, e.errorCode)
        coVerify(exactly = 0) { liveSessionRepository.save(any(), any()) }
    }

    @Test
    fun `시작 - 설정 문서가 없으면 동의하지 않은 것으로 본다`() = runTest {
        givenLinked(); givenAllowed()
        coEvery { settingsRepository.findByUserId(elder) } returns null

        val e = assertThrows<BusinessException> { service.startSession(guardian, elder, now) }

        assertEquals(ErrorCode.LIVE_NOT_ALLOWED, e.errorCode)
    }

    @Test
    fun `시작 - 5분 세션을 저장하고 시청 전용 토큰과 열람 기록을 남긴다`() = runTest {
        givenLinked(); givenAllowed(); givenLiveVideoEnabled(true)
        val saved = slot<LiveSession>()
        val history = slot<LoginHistory>()
        coEvery { liveSessionRepository.find(elder) } returns null
        coEvery { liveSessionRepository.save(capture(saved), now) } just runs
        coEvery { loginHistoryRepository.save(capture(history)) } coAnswers { history.captured }

        val result = service.startSession(guardian, elder, now)

        assertEquals(LiveSession(elder, guardian, now.plus(Duration.ofMinutes(5))), saved.captured)
        assertEquals("wss://test.livekit.cloud", result.serverUrl)
        assertEquals("live-elder1", result.room)
        val grant = video(claimsOf(result.token))
        assertEquals(true, grant["canSubscribe"])
        assertEquals(false, grant["canPublish"])
        assertEquals(SecurityEventType.LIVE_VIEW_START, history.captured.eventType)
        assertEquals(guardian, history.captured.userId)
        assertEquals(elder, history.captured.targetUserId)
        coVerify { rateLimiter.requireAllowed("rl:live-start:guardian1", 20, 3600) }
    }

    @Test
    fun `시작 - 진행 중 세션이 있으면 처음 시작한 보호자는 유지하고 만료만 연장한다`() = runTest {
        val other = "guardian2"
        coEvery { guardianLinkRepository.find(other, elder) } returns GuardianLink(other, elder)
        givenAllowed(); givenLiveVideoEnabled(true)
        val saved = slot<LiveSession>()
        coEvery { liveSessionRepository.find(elder) } returns LiveSession(elder, guardian, now.plusSeconds(60))
        coEvery { liveSessionRepository.save(capture(saved), now) } just runs
        coEvery { loginHistoryRepository.save(any()) } coAnswers { firstArg() }

        service.startSession(other, elder, now)

        assertEquals(guardian, saved.captured.startedBy)
        assertEquals(now.plus(Duration.ofMinutes(5)), saved.captured.expiresAt)
    }

    @Test
    fun `시작 - 열람 기록 저장이 실패해도 시청은 시작된다`() = runTest {
        givenLinked(); givenAllowed(); givenLiveVideoEnabled(true)
        coEvery { liveSessionRepository.find(elder) } returns null
        coEvery { liveSessionRepository.save(any(), now) } just runs
        coEvery { loginHistoryRepository.save(any()) } throws RuntimeException("firestore down")

        val result = service.startSession(guardian, elder, now)

        assertEquals("live-elder1", result.room)
    }

    // ── 송출 요청 FCM (4단계) ─────────────────────────────

    @Test
    fun `송출 요청 - 새 세션이면 피보호자에게 토큰 없이 data 메시지를 보낸다`() = runTest {
        givenLinked(); givenAllowed(); givenLiveVideoEnabled(true)
        val data = slot<Map<String, String>>()
        coEvery { liveSessionRepository.find(elder) } returns null
        coEvery { liveSessionRepository.save(any(), now) } just runs
        coEvery { loginHistoryRepository.save(any()) } coAnswers { firstArg() }
        coEvery { notificationService.sendDataMessage(elder, capture(data), Duration.ofMinutes(5)) } returns 1

        service.startSession(guardian, elder, now)

        assertEquals("live_request", data.captured["event"])
        assertEquals("live-elder1", data.captured["room"])
        assertEquals(now.plus(Duration.ofMinutes(5)).epochSecond.toString(), data.captured["expires_at"])
        assertTrue(data.captured.keys.none { it.contains("token") }, "${data.captured}")
    }

    @Test
    fun `송출 요청 - 연장이면 다시 보내지 않는다`() = runTest {
        givenLinked(); givenAllowed(); givenLiveVideoEnabled(true)
        coEvery { liveSessionRepository.find(elder) } returns LiveSession(elder, guardian, now.plusSeconds(60))
        coEvery { liveSessionRepository.save(any(), now) } just runs
        coEvery { loginHistoryRepository.save(any()) } coAnswers { firstArg() }

        service.startSession(guardian, elder, now)

        coVerify(exactly = 0) { notificationService.sendDataMessage(any(), any(), any()) }
    }

    @Test
    fun `송출 요청 - FCM 실패해도 보호자 시청 토큰은 발급된다`() = runTest {
        givenLinked(); givenAllowed(); givenLiveVideoEnabled(true)
        coEvery { liveSessionRepository.find(elder) } returns null
        coEvery { liveSessionRepository.save(any(), now) } just runs
        coEvery { loginHistoryRepository.save(any()) } coAnswers { firstArg() }
        coEvery { notificationService.sendDataMessage(any(), any(), any()) } throws RuntimeException("fcm down")

        val result = service.startSession(guardian, elder, now)

        assertEquals("live-elder1", result.room)
    }

    // ── 기기 확인·전달 결과 (W7) ─────────────────────────────

    @Test
    fun `기기 확인 - heartbeat가 6분보다 오래됐으면 LIVE_DEVICE_OFFLINE이고 세션·요청을 만들지 않는다`() = runTest {
        givenLinked(); givenAllowed(); givenLiveVideoEnabled(true)
        coEvery { liveSessionRepository.find(elder) } returns null
        givenHeartbeat(now.minus(Duration.ofMinutes(7)))

        val e = assertThrows<BusinessException> { service.startSession(guardian, elder, now) }

        assertEquals(ErrorCode.LIVE_DEVICE_OFFLINE, e.errorCode)
        coVerify(exactly = 0) { liveSessionRepository.save(any(), any()) }
        coVerify(exactly = 0) { notificationService.sendDataMessage(any(), any(), any()) }
    }

    @Test
    fun `기기 확인 - heartbeat 기록이 없으면 오프라인으로 본다`() = runTest {
        givenLinked(); givenAllowed(); givenLiveVideoEnabled(true)
        coEvery { liveSessionRepository.find(elder) } returns null
        coEvery { realtimeDataRepository.findByUserId(elder) } returns null

        val e = assertThrows<BusinessException> { service.startSession(guardian, elder, now) }

        assertEquals(ErrorCode.LIVE_DEVICE_OFFLINE, e.errorCode)
    }

    @Test
    fun `기기 확인 - 연장은 기기 확인을 하지 않고 request_delivered는 null`() = runTest {
        givenLinked(); givenAllowed(); givenLiveVideoEnabled(true)
        givenHeartbeat(now.minus(Duration.ofMinutes(30)))
        coEvery { liveSessionRepository.find(elder) } returns LiveSession(elder, guardian, now.plusSeconds(60))
        coEvery { liveSessionRepository.save(any(), now) } just runs
        coEvery { loginHistoryRepository.save(any()) } coAnswers { firstArg() }

        val result = service.startSession(guardian, elder, now)

        assertEquals(null, result.requestDelivered)
    }

    @Test
    fun `전달 결과 - 1대 이상 전달되면 request_delivered=true`() = runTest {
        givenLinked(); givenAllowed(); givenLiveVideoEnabled(true); givenNewSessionSaves()

        assertEquals(true, service.startSession(guardian, elder, now).requestDelivered)
    }

    @Test
    fun `전달 결과 - 토큰이 없어 0대면 request_delivered=false`() = runTest {
        givenLinked(); givenAllowed(); givenLiveVideoEnabled(true); givenNewSessionSaves()
        coEvery { notificationService.sendDataMessage(any(), any(), any()) } returns 0

        assertEquals(false, service.startSession(guardian, elder, now).requestDelivered)
    }

    @Test
    fun `전달 결과 - FCM 오류면 request_delivered=false`() = runTest {
        givenLinked(); givenAllowed(); givenLiveVideoEnabled(true); givenNewSessionSaves()
        coEvery { notificationService.sendDataMessage(any(), any(), any()) } throws RuntimeException("fcm down")

        assertEquals(false, service.startSession(guardian, elder, now).requestDelivered)
    }

    // ── 종료 ─────────────────────────────────────────────

    @Test
    fun `종료 - 세션과 방을 지우고(terminator) 종료 기록을 남긴다`() = runTest {
        givenLinked()
        val history = slot<LoginHistory>()
        coEvery { terminator.terminate(elder, "guardian_end") } returns true
        coEvery { loginHistoryRepository.save(capture(history)) } coAnswers { history.captured }

        service.endSession(guardian, elder)

        assertEquals(SecurityEventType.LIVE_VIEW_END, history.captured.eventType)
        assertEquals(elder, history.captured.targetUserId)
    }

    @Test
    fun `종료 - 이미 끝난 세션이면 오류 없이 넘어가고 기록하지 않는다`() = runTest {
        givenLinked()
        coEvery { terminator.terminate(elder, "guardian_end") } returns false

        service.endSession(guardian, elder)

        coVerify(exactly = 0) { loginHistoryRepository.save(any()) }
    }

    @Test
    fun `종료 - 연결되지 않은 보호자는 FORBIDDEN`() = runTest {
        coEvery { guardianLinkRepository.find(guardian, elder) } returns null

        val e = assertThrows<BusinessException> { service.endSession(guardian, elder) }

        assertEquals(ErrorCode.FORBIDDEN, e.errorCode)
        coVerify(exactly = 0) { terminator.terminate(any(), any()) }
    }

    // ── 송출 토큰 ─────────────────────────────────────────

    @Test
    fun `송출 토큰 - 진행 중 세션이 없으면 LIVE_SESSION_NOT_FOUND`() = runTest {
        coEvery { liveSessionRepository.find(elder) } returns null

        val e = assertThrows<BusinessException> { service.issuePublishToken(elder, now) }

        assertEquals(ErrorCode.LIVE_SESSION_NOT_FOUND, e.errorCode)
    }

    @Test
    fun `송출 토큰 - 만료 시각이 지난 세션은 없는 것으로 본다`() = runTest {
        coEvery { liveSessionRepository.find(elder) } returns LiveSession(elder, guardian, now.minusSeconds(1))

        val e = assertThrows<BusinessException> { service.issuePublishToken(elder, now) }

        assertEquals(ErrorCode.LIVE_SESSION_NOT_FOUND, e.errorCode)
    }

    @Test
    fun `송출 토큰 - 세션 시작 뒤 동의를 철회했으면 LIVE_NOT_ALLOWED`() = runTest {
        coEvery { liveSessionRepository.find(elder) } returns LiveSession(elder, guardian, now.plusSeconds(120))
        givenLiveVideoEnabled(false)

        val e = assertThrows<BusinessException> { service.issuePublishToken(elder, now) }

        assertEquals(ErrorCode.LIVE_NOT_ALLOWED, e.errorCode)
    }

    @Test
    fun `송출 토큰 - 송출 전용이고 남은 세션 시간만큼 유효하다`() = runTest {
        val expiresAt = now.plusSeconds(120)
        coEvery { liveSessionRepository.find(elder) } returns LiveSession(elder, guardian, expiresAt)
        givenLiveVideoEnabled(true)

        val issuedAround = Instant.now()
        val result = service.issuePublishToken(elder, now)

        val claims = claimsOf(result.token)
        assertEquals(true, video(claims)["canPublish"])
        assertEquals(false, video(claims)["canSubscribe"])
        assertEquals("elder-elder1", claims.subject)
        // 토큰 exp는 실제 발급 시각 + 남은 시간(120초)이다(LiveKit 토큰엔 iat·nbf가 없어 발급 직전 시각과 비교).
        val ttl = Duration.between(issuedAround, claims.expiration.toInstant())
        assertTrue(ttl <= Duration.ofSeconds(121) && ttl >= Duration.ofSeconds(115), "ttl=$ttl")
    }

    // ── 자동 연장 (5-E) ─────────────────────────────────────

    @Test
    fun `연장 - rate limit을 세지 않고 열람 기록도 남기지 않는다 (자동 연장)`() = runTest {
        givenLinked(); givenAllowed(); givenLiveVideoEnabled(true)
        coEvery { liveSessionRepository.find(elder) } returns LiveSession(elder, guardian, now.plusSeconds(60))
        coEvery { liveSessionRepository.save(any(), now) } just runs

        service.startSession(guardian, elder, now)

        coVerify(exactly = 0) { rateLimiter.requireAllowed(any(), any(), any()) }
        coVerify(exactly = 0) { loginHistoryRepository.save(any()) }
    }

    @Test
    fun `연장 - 시간당 한도를 넘긴 상태여도 진행 중 세션은 이어진다`() = runTest {
        givenLinked(); givenLiveVideoEnabled(true)
        coEvery { rateLimiter.requireAllowed(any(), any(), any()) } throws
            BusinessException(ErrorCode.TOO_MANY_REQUESTS)
        coEvery { liveSessionRepository.find(elder) } returns LiveSession(elder, guardian, now.plusSeconds(60))
        coEvery { liveSessionRepository.save(any(), now) } just runs

        val result = service.startSession(guardian, elder, now)

        assertEquals(now.plus(Duration.ofMinutes(5)), result.expiresAt.atZone(java.time.ZoneId.systemDefault()).toInstant())
    }

    @Test
    fun `연장 - 그사이 동의를 철회했으면 연장도 LIVE_NOT_ALLOWED`() = runTest {
        givenLinked(); givenAllowed(); givenLiveVideoEnabled(false)
        coEvery { liveSessionRepository.find(elder) } returns LiveSession(elder, guardian, now.plusSeconds(60))

        val e = assertThrows<BusinessException> { service.startSession(guardian, elder, now) }

        assertEquals(ErrorCode.LIVE_NOT_ALLOWED, e.errorCode)
        coVerify(exactly = 0) { liveSessionRepository.save(any(), any()) }
    }

    @Test
    fun `새 세션 - 시간당 한도를 넘기면 TOO_MANY_REQUESTS이고 세션을 만들지 않는다`() = runTest {
        givenLinked(); givenLiveVideoEnabled(true)
        coEvery { rateLimiter.requireAllowed(any(), any(), any()) } throws
            BusinessException(ErrorCode.TOO_MANY_REQUESTS)
        coEvery { liveSessionRepository.find(elder) } returns null

        val e = assertThrows<BusinessException> { service.startSession(guardian, elder, now) }

        assertEquals(ErrorCode.TOO_MANY_REQUESTS, e.errorCode)
        coVerify(exactly = 0) { liveSessionRepository.save(any(), any()) }
    }
}
