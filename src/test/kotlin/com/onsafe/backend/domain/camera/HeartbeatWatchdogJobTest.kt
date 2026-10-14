package com.onsafe.backend.domain.camera

import com.onsafe.backend.common.exception.BusinessException
import com.onsafe.backend.common.exception.ErrorCode
import com.onsafe.backend.domain.camera.model.entity.RealtimeData
import com.onsafe.backend.domain.camera.repository.RealtimeDataRepository
import com.onsafe.backend.domain.camera.service.HeartbeatWatchdogJob
import com.onsafe.backend.domain.camera.service.StreamAliveStore
import com.onsafe.backend.domain.guardian.repository.GuardianLinkRepository
import com.onsafe.backend.domain.notification.service.NotificationService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

class HeartbeatWatchdogJobTest {

    private val realtimeDataRepository: RealtimeDataRepository = mockk(relaxUnitFun = true)
    private val guardianLinkRepository: GuardianLinkRepository = mockk()
    private val notificationService: NotificationService = mockk(relaxUnitFun = true)
    private val streamAliveStore: StreamAliveStore = mockk()
    private lateinit var job: HeartbeatWatchdogJob

    private val detectedBefore = LocalDateTime.now().minusMinutes(5)

    private fun online(
        userId: String = "elder",
        detectedAt: LocalDateTime? = null,
        notifiedAt: LocalDateTime? = null,
    ) = RealtimeData(
        userId = userId,
        lastHeartbeatAt = LocalDateTime.now(),
        isOnline = true,
        streamDownDetectedAt = detectedAt,
        lastStreamDownNotifiedAt = notifiedAt,
    )

    @BeforeEach
    fun setUp() {
        job = HeartbeatWatchdogJob(realtimeDataRepository, guardianLinkRepository, notificationService, streamAliveStore)
        coEvery { realtimeDataRepository.findOfflineCandidates(any(), any()) } returns emptyList()
        coEvery { realtimeDataRepository.findOnlineCandidates(any(), any()) } returns emptyList()
        coEvery { streamAliveStore.aliveUserIds(any()) } returns emptySet()
    }

    // ── 오프라인(기존 동작) ───────────────────────────────────────

    @Test
    fun `오프라인 - 처음 감지하면 보호자에게 알리고 오프라인으로 표시한다`() = runTest {
        coEvery { realtimeDataRepository.findOfflineCandidates(any(), any()) } returns
            listOf(RealtimeData(userId = "elder", isOnline = true))

        job.run()

        coVerify(exactly = 1) { notificationService.notifyGuardiansCameraOffline("elder") }
        coVerify(exactly = 1) { realtimeDataRepository.markOffline("elder", any()) }
    }

    // ── 감지 중단(B5) ─────────────────────────────────────────────

    @Test
    fun `감지 중단 - 처음 발견하면 시각만 기록하고 알리지 않는다`() = runTest {
        coEvery { realtimeDataRepository.findOnlineCandidates(any(), any()) } returns listOf(online())

        job.run()

        coVerify(exactly = 1) { realtimeDataRepository.updateStreamDown("elder", detectedAt = any(), notifiedAt = null) }
        coVerify(exactly = 0) { realtimeDataRepository.updateStreamDown("elder", detectedAt = null, notifiedAt = null) }
        coVerify(exactly = 0) { notificationService.notifyGuardiansStreamDown(any()) }
    }

    @Test
    fun `감지 중단 - 다음 실행에도 신호가 없으면 1회 알리고 알림 시각을 기록한다`() = runTest {
        coEvery { realtimeDataRepository.findOnlineCandidates(any(), any()) } returns
            listOf(online(detectedAt = detectedBefore))

        job.run()

        coVerify(exactly = 1) { notificationService.notifyGuardiansStreamDown("elder") }
        coVerify(exactly = 1) {
            realtimeDataRepository.updateStreamDown("elder", detectedAt = detectedBefore, notifiedAt = any())
        }
    }

    @Test
    fun `감지 중단 - 이미 알렸으면 복구 전까지 다시 보내지 않는다`() = runTest {
        coEvery { realtimeDataRepository.findOnlineCandidates(any(), any()) } returns
            listOf(online(detectedAt = detectedBefore, notifiedAt = detectedBefore))

        job.run()

        coVerify(exactly = 0) { notificationService.notifyGuardiansStreamDown(any()) }
        coVerify(exactly = 0) { realtimeDataRepository.updateStreamDown(any(), any(), any()) }
    }

    @Test
    fun `감지 중단 - 알린 뒤 신호가 돌아오면 복구 알림을 보내고 상태를 비운다`() = runTest {
        coEvery { realtimeDataRepository.findOnlineCandidates(any(), any()) } returns
            listOf(online(detectedAt = detectedBefore, notifiedAt = detectedBefore))
        coEvery { streamAliveStore.aliveUserIds(listOf("elder")) } returns setOf("elder")

        job.run()

        coVerify(exactly = 1) { notificationService.notifyGuardiansStreamRecovered("elder") }
        coVerify(exactly = 1) { realtimeDataRepository.updateStreamDown("elder", detectedAt = null, notifiedAt = null) }
    }

    @Test
    fun `감지 중단 - 알리기 전에 신호가 돌아오면 복구 알림 없이 기록만 비운다`() = runTest {
        coEvery { realtimeDataRepository.findOnlineCandidates(any(), any()) } returns
            listOf(online(detectedAt = detectedBefore))
        coEvery { streamAliveStore.aliveUserIds(listOf("elder")) } returns setOf("elder")

        job.run()

        coVerify(exactly = 0) { notificationService.notifyGuardiansStreamRecovered(any()) }
        coVerify(exactly = 1) { realtimeDataRepository.updateStreamDown("elder", detectedAt = null, notifiedAt = null) }
    }

    @Test
    fun `감지 중단 - 신호가 있고 기록도 없으면 아무것도 쓰지 않는다`() = runTest {
        coEvery { realtimeDataRepository.findOnlineCandidates(any(), any()) } returns listOf(online())
        coEvery { streamAliveStore.aliveUserIds(listOf("elder")) } returns setOf("elder")

        job.run()

        coVerify(exactly = 0) { realtimeDataRepository.updateStreamDown(any(), any(), any()) }
        coVerify(exactly = 0) { notificationService.notifyGuardiansStreamRecovered(any()) }
    }

    @Test
    fun `감지 중단 - Redis를 못 읽으면 이번 실행은 건너뛴다`() = runTest {
        coEvery { realtimeDataRepository.findOnlineCandidates(any(), any()) } returns
            listOf(online(detectedAt = detectedBefore))
        coEvery { streamAliveStore.aliveUserIds(any()) } throws BusinessException(ErrorCode.REDIS_UNAVAILABLE)

        job.run()

        coVerify(exactly = 0) { notificationService.notifyGuardiansStreamDown(any()) }
        coVerify(exactly = 0) { realtimeDataRepository.updateStreamDown(any(), any(), any()) }
    }

    @Test
    fun `감지 중단 - 한 사용자 처리 실패가 다른 사용자 처리를 막지 않는다`() = runTest {
        coEvery { realtimeDataRepository.findOnlineCandidates(any(), any()) } returns
            listOf(online(userId = "a", detectedAt = detectedBefore), online(userId = "b", detectedAt = detectedBefore))
        coEvery { notificationService.notifyGuardiansStreamDown("a") } throws RuntimeException("boom")

        job.run()

        coVerify(exactly = 1) { notificationService.notifyGuardiansStreamDown("b") }
        coVerify(exactly = 1) { realtimeDataRepository.updateStreamDown("b", detectedAt = detectedBefore, notifiedAt = any()) }
    }

    @Test
    fun `오프라인 후보가 없어도 감지 중단 검사는 실행한다`() = runTest {
        coEvery { realtimeDataRepository.findOnlineCandidates(any(), any()) } returns listOf(online())

        job.run()

        coVerify(exactly = 1) { realtimeDataRepository.findOnlineCandidates(any(), any()) }
        coVerify(exactly = 1) { streamAliveStore.aliveUserIds(listOf("elder")) }
    }
}
