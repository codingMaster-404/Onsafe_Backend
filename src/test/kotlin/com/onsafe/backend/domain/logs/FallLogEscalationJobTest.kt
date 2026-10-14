package com.onsafe.backend.domain.logs

import com.onsafe.backend.domain.camera.model.entity.RealtimeData
import com.onsafe.backend.domain.camera.repository.RealtimeDataRepository
import com.onsafe.backend.domain.logs.model.entity.FallLog
import com.onsafe.backend.domain.logs.repository.FallLogRepository
import com.onsafe.backend.domain.logs.service.FallLogEscalationJob
import com.onsafe.backend.domain.notification.service.NotificationService
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

class FallLogEscalationJobTest {

    private val fallLogRepository: FallLogRepository = mockk()
    private val notificationService: NotificationService = mockk()
    private val realtimeDataRepository: RealtimeDataRepository = mockk()
    private lateinit var job: FallLogEscalationJob

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
        job = FallLogEscalationJob(fallLogRepository, notificationService, realtimeDataRepository)
        // 기본은 카메라 온라인(방금 heartbeat) — 오프라인 문구 케이스만 각 테스트에서 덮어쓴다.
        coEvery { realtimeDataRepository.findByUserId(any()) } returns RealtimeData(userId = "any", lastHeartbeatAt = LocalDateTime.now())
    }

    // ── 사용자당 최신 이벤트만 에스컬레이션 ─────────────────────────────

    @Test
    fun `같은 사용자의 미확인 로그가 여러 개면 가장 최근 것만 클레임한다`() = runTest {
        val old = log("old", "userA", minutesAgo = 400)   // danger_cd 만료 후 새로 생긴 예전 이벤트
        val recent = log("recent", "userA", minutesAgo = 20)

        coEvery { fallLogRepository.findUnconfirmedDangerLogs(any()) } returns listOf(old, recent)
        coEvery { fallLogRepository.claimReminder("recent", any()) } returns recent.copy(lastReminderAt = LocalDateTime.now())
        coEvery {
            notificationService.notifyElderAndGuardians(
                elderUserId = any(), title = any(), body = any(), logId = any(), score = any(), fall = any(), data = any(), guardianBody = any()
            )
        } just Runs

        job.run()

        coVerify(exactly = 1) { fallLogRepository.claimReminder("recent", any()) }
        coVerify(exactly = 0) { fallLogRepository.claimReminder("old", any()) }
    }

    // ── 클레임 실패(경합/아직 안 됨) 시 알림 미발송 ──────────────────────

    @Test
    fun `claimReminder가 null이면 알림을 보내지 않는다`() = runTest {
        val candidate = log("log1", "userB", minutesAgo = 5)  // 아직 리마인더 주기 미도달

        coEvery { fallLogRepository.findUnconfirmedDangerLogs(any()) } returns listOf(candidate)
        coEvery { fallLogRepository.claimReminder("log1", any()) } returns null

        job.run()

        coVerify(exactly = 0) {
            notificationService.notifyElderAndGuardians(
                elderUserId = any(), title = any(), body = any(), logId = any(), score = any(), fall = any(), data = any(), guardianBody = any()
            )
        }
    }

    // ── 클레임 성공 시 올바른 사용자에게 알림 발송 ───────────────────────

    @Test
    fun `claimReminder가 성공하면 해당 사용자에게 알림을 보낸다`() = runTest {
        val candidate = log("log2", "userC", minutesAgo = 30)
        val claimed = candidate.copy(lastReminderAt = LocalDateTime.now())
        val elderIdSlot = slot<String>()
        val logIdSlot = slot<String>()

        coEvery { fallLogRepository.findUnconfirmedDangerLogs(any()) } returns listOf(candidate)
        coEvery { fallLogRepository.claimReminder("log2", any()) } returns claimed
        coEvery {
            notificationService.notifyElderAndGuardians(
                elderUserId = capture(elderIdSlot), title = any(), body = any(),
                logId = capture(logIdSlot), score = any(), fall = any(), data = any(), guardianBody = any()
            )
        } just Runs

        job.run()

        assertEquals("userC", elderIdSlot.captured)
        assertEquals("log2", logIdSlot.captured)
    }

    // ── 여러 사용자는 독립적으로 처리 ────────────────────────────────

    @Test
    fun `서로 다른 사용자의 후보는 각각 클레임을 시도한다`() = runTest {
        val userA = log("a1", "userA", minutesAgo = 20)
        val userB = log("b1", "userB", minutesAgo = 20)

        coEvery { fallLogRepository.findUnconfirmedDangerLogs(any()) } returns listOf(userA, userB)
        coEvery { fallLogRepository.claimReminder(any(), any()) } returnsMany listOf(
            userA.copy(lastReminderAt = LocalDateTime.now()),
            userB.copy(lastReminderAt = LocalDateTime.now())
        )
        coEvery {
            notificationService.notifyElderAndGuardians(
                elderUserId = any(), title = any(), body = any(), logId = any(), score = any(), fall = any(), data = any(), guardianBody = any()
            )
        } just Runs

        job.run()

        coVerify(exactly = 1) { fallLogRepository.claimReminder("a1", any()) }
        coVerify(exactly = 1) { fallLogRepository.claimReminder("b1", any()) }
    }

    // ── 카메라 오프라인 중 재알림 문구 (S6) ───────────────────────────

    private suspend fun runAndCaptureGuardianBody(): String? {
        val candidate = log("log9", "userD", minutesAgo = 30)
        val bodies = mutableListOf<String?>()
        coEvery { fallLogRepository.findUnconfirmedDangerLogs(any()) } returns listOf(candidate)
        coEvery { fallLogRepository.claimReminder("log9", any()) } returns candidate.copy(lastReminderAt = LocalDateTime.now())
        coEvery {
            notificationService.notifyElderAndGuardians(
                elderUserId = any(), title = any(), body = any(), logId = any(), score = any(), fall = any(), data = any(),
                guardianBody = captureNullable(bodies)
            )
        } just Runs

        job.run()

        return bodies.single()
    }

    @Test
    fun `카메라 heartbeat가 6분을 넘겼으면 보호자 본문을 직접 연락 안내로 바꾼다`() = runTest {
        coEvery { realtimeDataRepository.findByUserId("userD") } returns
            RealtimeData(userId = "userD", lastHeartbeatAt = LocalDateTime.now().minusMinutes(10))

        assertEquals(FallLogEscalationJob.OFFLINE_GUARDIAN_BODY, runAndCaptureGuardianBody())
    }

    @Test
    fun `heartbeat 기록이 없어도 오프라인으로 본다`() = runTest {
        coEvery { realtimeDataRepository.findByUserId("userD") } returns null

        assertEquals(FallLogEscalationJob.OFFLINE_GUARDIAN_BODY, runAndCaptureGuardianBody())
    }

    @Test
    fun `카메라가 온라인이면 기존 문구 그대로 보낸다`() = runTest {
        assertNull(runAndCaptureGuardianBody())
    }

    @Test
    fun `카메라 상태 조회가 실패하면 기존 문구로 재알림은 그대로 보낸다`() = runTest {
        coEvery { realtimeDataRepository.findByUserId("userD") } throws RuntimeException("firestore down")

        assertNull(runAndCaptureGuardianBody())
    }
}