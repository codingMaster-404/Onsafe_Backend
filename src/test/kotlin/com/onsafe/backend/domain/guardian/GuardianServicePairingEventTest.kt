package com.onsafe.backend.domain.guardian

import com.onsafe.backend.common.ratelimit.RateLimiter
import com.onsafe.backend.common.security.VerificationCodeGenerator
import com.onsafe.backend.domain.auth.model.entity.LoginHistory
import com.onsafe.backend.domain.auth.model.entity.SecurityEventType
import com.onsafe.backend.domain.auth.repository.LoginHistoryRepository
import com.onsafe.backend.domain.guardian.model.entity.GuardianLink
import com.onsafe.backend.domain.guardian.repository.GuardianLinkRepository
import com.onsafe.backend.domain.guardian.service.GuardianService
import com.onsafe.backend.domain.live.service.LiveSessionTerminator
import com.onsafe.backend.domain.notification.service.NotificationService
import com.onsafe.backend.domain.settings.service.SettingsService
import com.onsafe.backend.domain.user.model.entity.User
import com.onsafe.backend.domain.user.repository.UserRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.data.redis.core.ReactiveValueOperations
import reactor.core.publisher.Mono
import java.time.LocalDateTime

/** 연결 성립·해제·교체를 보안 이벤트로 남긴다(S2) — 행위자는 user_id, 상대는 target_user_id. */
class GuardianServicePairingEventTest {

    private val guardianLinkRepository: GuardianLinkRepository = mockk()
    private val userRepository: UserRepository = mockk()
    private val notificationService: NotificationService = mockk(relaxed = true)
    private val redis: ReactiveStringRedisTemplate = mockk()
    private val valueOps: ReactiveValueOperations<String, String> = mockk()
    private val rateLimiter: RateLimiter = mockk(relaxUnitFun = true)
    private val codeGenerator: VerificationCodeGenerator = mockk()
    private val terminator: LiveSessionTerminator = mockk(relaxed = true)
    private val settingsService: SettingsService = mockk(relaxed = true)
    private val loginHistoryRepository: LoginHistoryRepository = mockk()
    private val service = GuardianService(
        guardianLinkRepository, userRepository, notificationService, redis, rateLimiter, codeGenerator, terminator,
        settingsService, loginHistoryRepository, mockk(relaxed = true)
    ).also { every { redis.opsForValue() } returns valueOps }

    private fun user(id: String) = User(
        userId = id, password = "x", name = id, phone = "010-0000-0000", mail = "$id@test.com",
        createdAt = LocalDateTime.now()
    )

    private fun captureEvents(): MutableList<LoginHistory> {
        val events = mutableListOf<LoginHistory>()
        coEvery { loginHistoryRepository.save(capture(events)) } answers { firstArg() }
        return events
    }

    private fun givenApprovable(displaced: List<GuardianLink>) {
        every { valueOps.getAndDelete("pairing_request:req1") } returns Mono.just("guardianNew:elder1")
        coEvery { userRepository.findByUserId("elder1") } returns user("elder1")
        coEvery { userRepository.findByUserId("guardianNew") } returns user("guardianNew")
        coEvery { guardianLinkRepository.createOrReplace(any()) } returns
            GuardianLinkRepository.ReplaceResult.Created(displaced = displaced)
    }

    private fun LoginHistory.summary() = Triple(eventType, userId, targetUserId)

    @Test
    fun `승인 - 피보호자 명의로 성립 1건을 남긴다`() = runTest {
        val events = captureEvents()
        givenApprovable(displaced = emptyList())

        service.approvePairingRequest("elder1", "req1")

        assertEquals(
            listOf(Triple(SecurityEventType.PAIRING_CREATED, "elder1", "guardianNew")),
            events.map { it.summary() }
        )
    }

    @Test
    fun `승인 - 밀려난 관계마다 밀려난 상대를 대상으로 교체를 남긴다`() = runTest {
        val events = captureEvents()
        givenApprovable(
            displaced = listOf(
                GuardianLink("guardianOld", "elder1"),   // 피보호자의 기존 보호자
                GuardianLink("guardianNew", "elderOld"), // 새 보호자가 보던 기존 피보호자
            )
        )

        service.approvePairingRequest("elder1", "req1")

        assertEquals(
            listOf(
                Triple(SecurityEventType.PAIRING_CREATED, "elder1", "guardianNew"),
                Triple(SecurityEventType.PAIRING_DISPLACED, "elder1", "guardianOld"),
                Triple(SecurityEventType.PAIRING_DISPLACED, "elder1", "elderOld"),
            ),
            events.map { it.summary() }
        )
    }

    @Test
    fun `해제 - 해제한 사람 명의로 상대를 대상으로 남긴다`() = runTest {
        val events = captureEvents()
        coEvery { guardianLinkRepository.delete("elder1", "guardian1") } returns false
        coEvery { guardianLinkRepository.delete("guardian1", "elder1") } returns true
        coEvery { userRepository.findByUserId("elder1") } returns user("elder1")

        service.unpair("elder1", "guardian1")

        assertEquals(
            listOf(Triple(SecurityEventType.PAIRING_UNPAIRED, "elder1", "guardian1")),
            events.map { it.summary() }
        )
    }

    @Test
    fun `해제 - 연결이 없으면 기록하지 않는다`() = runTest {
        coEvery { guardianLinkRepository.delete(any(), any()) } returns false

        runCatching { service.unpair("guardian1", "elder1") }

        coVerify(exactly = 0) { loginHistoryRepository.save(any()) }
    }

    @Test
    fun `기록 실패 - 승인과 해제는 그대로 완료된다`() = runTest {
        coEvery { loginHistoryRepository.save(any()) } throws RuntimeException("firestore down")
        givenApprovable(displaced = listOf(GuardianLink("guardianOld", "elder1")))
        coEvery { guardianLinkRepository.delete("guardianNew", "elder1") } returns true

        val ward = service.approvePairingRequest("elder1", "req1")
        service.unpair("guardianNew", "elder1")

        assertEquals("elder1", ward.userId)
        coVerify(exactly = 1) { notificationService.sendNotification(match { it.userId == "guardianOld" }) }
        coVerify(exactly = 1) { notificationService.sendNotification(match { it.userId == "elder1" && it.data?.get("event") == "pairing_unpaired" }) }
    }

    // ── 해제·교체 시 보호자 알림함 정리 (S1) ─────────────────────

    @Test
    fun `해제 - 피보호자가 해제해도 보호자 알림함을 정리한 뒤 해제 통지를 보낸다`() = runTest {
        captureEvents()
        coEvery { guardianLinkRepository.delete("elder1", "guardian1") } returns false
        coEvery { guardianLinkRepository.delete("guardian1", "elder1") } returns true
        coEvery { userRepository.findByUserId("elder1") } returns user("elder1")

        service.unpair("elder1", "guardian1")

        io.mockk.coVerifyOrder {
            notificationService.purgeGuardianInbox("guardian1", "elder1")
            notificationService.sendNotification(match { it.data?.get("event") == "pairing_unpaired" })
        }
    }

    @Test
    fun `교체 - 밀려난 관계마다 그 보호자 알림함을 정리하고 승인 완료 알림에는 대상을 남긴다`() = runTest {
        captureEvents()
        givenApprovable(
            displaced = listOf(
                GuardianLink("guardianOld", "elder1"),
                GuardianLink("guardianNew", "elderOld"),
            )
        )

        service.approvePairingRequest("elder1", "req1")

        coVerify(exactly = 1) { notificationService.purgeGuardianInbox("guardianOld", "elder1") }
        coVerify(exactly = 1) { notificationService.purgeGuardianInbox("guardianNew", "elderOld") }
        coVerify(exactly = 1) {
            notificationService.sendNotification(match { it.data?.get("event") == "pairing_approved" && it.subjectUserId == "elder1" })
        }
        // 교체 통지는 정리 대상이 아니다 — "왜 끊겼는지"가 알림함에 남아야 한다.
        coVerify(exactly = 0) {
            notificationService.sendNotification(match { it.data?.get("event") == "pairing_displaced" && it.subjectUserId != null })
        }
    }

    @Test
    fun `알림함 정리 실패 - 해제는 그대로 완료되고 통지도 나간다`() = runTest {
        captureEvents()
        coEvery { guardianLinkRepository.delete("guardian1", "elder1") } returns true
        coEvery { userRepository.findByUserId("guardian1") } returns user("guardian1")
        coEvery { notificationService.purgeGuardianInbox(any(), any()) } throws RuntimeException("firestore down")

        service.unpair("guardian1", "elder1")

        coVerify(exactly = 1) { notificationService.sendNotification(match { it.userId == "elder1" }) }
    }
}
