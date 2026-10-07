package com.onsafe.backend.domain.guardian

import com.onsafe.backend.common.ratelimit.RateLimiter
import com.onsafe.backend.common.security.VerificationCodeGenerator
import com.onsafe.backend.domain.guardian.model.entity.GuardianLink
import com.onsafe.backend.domain.guardian.repository.GuardianLinkRepository
import com.onsafe.backend.domain.guardian.service.GuardianService
import com.onsafe.backend.domain.live.service.LiveSessionTerminator
import com.onsafe.backend.domain.notification.service.NotificationService
import com.onsafe.backend.domain.settings.model.dto.LiveVideoSettingsRequest
import com.onsafe.backend.domain.settings.service.SettingsService
import com.onsafe.backend.domain.user.model.entity.User
import com.onsafe.backend.domain.user.repository.UserRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.data.redis.core.ReactiveValueOperations
import reactor.core.publisher.Mono
import java.time.LocalDateTime

/** 연결이 끊기는 순간 진행 중 실시간 영상도 끊는다(W4) — 연결 해제·재페어링으로 밀려남. */
class GuardianServiceLiveTest {

    private val guardianLinkRepository: GuardianLinkRepository = mockk()
    private val userRepository: UserRepository = mockk()
    private val notificationService: NotificationService = mockk(relaxed = true)
    private val redis: ReactiveStringRedisTemplate = mockk()
    private val valueOps: ReactiveValueOperations<String, String> = mockk()
    private val rateLimiter: RateLimiter = mockk(relaxUnitFun = true)
    private val codeGenerator: VerificationCodeGenerator = mockk()
    private val terminator: LiveSessionTerminator = mockk(relaxed = true)
    private val settingsService: SettingsService = mockk(relaxed = true)
    private val service = GuardianService(
        guardianLinkRepository, userRepository, notificationService, redis, rateLimiter, codeGenerator, terminator,
        settingsService
    ).also { every { redis.opsForValue() } returns valueOps }

    private fun user(id: String) = User(
        userId = id, password = "x", name = id, phone = "010-0000-0000", mail = "$id@test.com",
        createdAt = LocalDateTime.now()
    )

    @Test
    fun `연결 해제 - 보호자가 해제하면 상대(피보호자)의 방을 끊는다`() = runTest {
        coEvery { guardianLinkRepository.delete("guardian1", "elder1") } returns true
        coEvery { userRepository.findByUserId("guardian1") } returns user("guardian1")

        service.unpair("guardian1", "elder1")

        coVerify(exactly = 1) { terminator.terminate("elder1", "unpaired") }
    }

    @Test
    fun `연결 해제 - 피보호자가 해제하면 자기 방을 끊는다`() = runTest {
        coEvery { guardianLinkRepository.delete("elder1", "guardian1") } returns false
        coEvery { guardianLinkRepository.delete("guardian1", "elder1") } returns true
        coEvery { userRepository.findByUserId("elder1") } returns user("elder1")

        service.unpair("elder1", "guardian1")

        coVerify(exactly = 1) { terminator.terminate("elder1", "unpaired") }
    }

    @Test
    fun `재페어링 - 밀려난 관계의 피보호자 방을 모두 끊는다`() = runTest {
        every { valueOps.getAndDelete("pairing_request:req1") } returns Mono.just("guardianNew:elder1")
        coEvery { userRepository.findByUserId("elder1") } returns user("elder1")
        coEvery { userRepository.findByUserId("guardianNew") } returns user("guardianNew")
        coEvery { guardianLinkRepository.createOrReplace(any()) } returns GuardianLinkRepository.ReplaceResult.Created(
            displaced = listOf(
                GuardianLink("guardianOld", "elder1"),   // 피보호자의 기존 보호자
                GuardianLink("guardianNew", "elderOld"), // 새 보호자가 보던 기존 피보호자
            )
        )

        service.approvePairingRequest("elder1", "req1")

        coVerify(exactly = 1) { terminator.terminate("elder1", "pairing_displaced") }
        coVerify(exactly = 1) { terminator.terminate("elderOld", "pairing_displaced") }
    }

    // ── 승인 시 영상 동의 (W9) ─────────────────────────────

    private fun givenApprovable() {
        every { valueOps.getAndDelete("pairing_request:req1") } returns Mono.just("guardianNew:elder1")
        coEvery { userRepository.findByUserId("elder1") } returns user("elder1")
        coEvery { userRepository.findByUserId("guardianNew") } returns user("guardianNew")
        coEvery { guardianLinkRepository.createOrReplace(any()) } returns
            GuardianLinkRepository.ReplaceResult.Created(displaced = emptyList())
    }

    @Test
    fun `승인 - 영상 허용 값을 보내면 피보호자 설정에 반영한다`() = runTest {
        givenApprovable()

        service.approvePairingRequest("elder1", "req1", liveVideoEnabled = true)

        coVerify(exactly = 1) { settingsService.updateLiveVideoSettings("elder1", LiveVideoSettingsRequest(enabled = true)) }
    }

    @Test
    fun `승인 - 값을 생략하면 영상 설정을 건드리지 않는다`() = runTest {
        givenApprovable()

        service.approvePairingRequest("elder1", "req1")

        coVerify(exactly = 0) { settingsService.updateLiveVideoSettings(any(), any()) }
    }

    @Test
    fun `승인 - 영상 설정 저장이 실패해도 연결 승인은 완료된다`() = runTest {
        givenApprovable()
        coEvery { settingsService.updateLiveVideoSettings(any(), any()) } throws RuntimeException("firestore down")

        val ward = service.approvePairingRequest("elder1", "req1", liveVideoEnabled = false)

        org.junit.jupiter.api.Assertions.assertEquals("elder1", ward.userId)
    }
}
