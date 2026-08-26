package com.onsafe.backend.domain.logs

import com.onsafe.backend.domain.logs.model.entity.FallLog
import com.onsafe.backend.domain.logs.repository.FallLogRepository
import com.onsafe.backend.domain.logs.service.FallLogEscalationScheduler
import com.onsafe.backend.domain.notification.service.NotificationService
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

class FallLogEscalationSchedulerTest {

    private val fallLogRepository: FallLogRepository = mockk()
    private val notificationService: NotificationService = mockk()
    private lateinit var scheduler: FallLogEscalationScheduler

    private fun log(logId: String, userId: String, minutesAgo: Long) = FallLog(
        logId = logId,
        deviceId = "device1",
        userId = userId,
        score = 90f,
        fall = false,
        isConfirmed = false,
        timestamp = LocalDateTime.now().minusMinutes(minutesAgo)
    )

    @BeforeEach
    fun setUp() {
        scheduler = FallLogEscalationScheduler(fallLogRepository, notificationService)
    }

    // ── 사용자당 최신 이벤트만 에스컬레이션 ─────────────────────────────

    @Test
    fun `같은 사용자의 미확인 로그가 여러 개면 가장 최근 것만 클레임한다`() {
        val old = log("old", "userA", minutesAgo = 400)   // danger_cd 만료 후 새로 생긴 예전 이벤트
        val recent = log("recent", "userA", minutesAgo = 20)

        coEvery { fallLogRepository.findUnconfirmedDangerLogs(any()) } returns listOf(old, recent)
        coEvery { fallLogRepository.claimReminder("recent", any()) } returns recent.copy(lastReminderAt = LocalDateTime.now())
        coEvery {
            notificationService.notifyElderAndGuardians(
                elderUserId = any(), title = any(), body = any(), logId = any(), score = any(), fall = any(), data = any()
            )
        } just Runs

        scheduler.checkEscalations()

        coVerify(exactly = 1) { fallLogRepository.claimReminder("recent", any()) }
        coVerify(exactly = 0) { fallLogRepository.claimReminder("old", any()) }
    }

    // ── 클레임 실패(경합/아직 안 됨) 시 알림 미발송 ──────────────────────

    @Test
    fun `claimReminder가 null이면 알림을 보내지 않는다`() {
        val candidate = log("log1", "userB", minutesAgo = 5)  // 아직 리마인더 주기 미도달

        coEvery { fallLogRepository.findUnconfirmedDangerLogs(any()) } returns listOf(candidate)
        coEvery { fallLogRepository.claimReminder("log1", any()) } returns null

        scheduler.checkEscalations()

        coVerify(exactly = 0) {
            notificationService.notifyElderAndGuardians(
                elderUserId = any(), title = any(), body = any(), logId = any(), score = any(), fall = any(), data = any()
            )
        }
    }

    // ── 클레임 성공 시 올바른 사용자에게 알림 발송 ───────────────────────

    @Test
    fun `claimReminder가 성공하면 해당 사용자에게 알림을 보낸다`() {
        val candidate = log("log2", "userC", minutesAgo = 30)
        val claimed = candidate.copy(lastReminderAt = LocalDateTime.now())
        val elderIdSlot = slot<String>()
        val logIdSlot = slot<String>()

        coEvery { fallLogRepository.findUnconfirmedDangerLogs(any()) } returns listOf(candidate)
        coEvery { fallLogRepository.claimReminder("log2", any()) } returns claimed
        coEvery {
            notificationService.notifyElderAndGuardians(
                elderUserId = capture(elderIdSlot), title = any(), body = any(),
                logId = capture(logIdSlot), score = any(), fall = any(), data = any()
            )
        } just Runs

        scheduler.checkEscalations()

        assertEquals("userC", elderIdSlot.captured)
        assertEquals("log2", logIdSlot.captured)
    }

    // ── 여러 사용자는 독립적으로 처리 ────────────────────────────────

    @Test
    fun `서로 다른 사용자의 후보는 각각 클레임을 시도한다`() {
        val userA = log("a1", "userA", minutesAgo = 20)
        val userB = log("b1", "userB", minutesAgo = 20)

        coEvery { fallLogRepository.findUnconfirmedDangerLogs(any()) } returns listOf(userA, userB)
        coEvery { fallLogRepository.claimReminder(any(), any()) } returnsMany listOf(
            userA.copy(lastReminderAt = LocalDateTime.now()),
            userB.copy(lastReminderAt = LocalDateTime.now())
        )
        coEvery {
            notificationService.notifyElderAndGuardians(
                elderUserId = any(), title = any(), body = any(), logId = any(), score = any(), fall = any(), data = any()
            )
        } just Runs

        scheduler.checkEscalations()

        coVerify(exactly = 1) { fallLogRepository.claimReminder("a1", any()) }
        coVerify(exactly = 1) { fallLogRepository.claimReminder("b1", any()) }
    }
}
