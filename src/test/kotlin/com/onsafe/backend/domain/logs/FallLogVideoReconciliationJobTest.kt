package com.onsafe.backend.domain.logs

import com.onsafe.backend.common.storage.StorageService
import com.onsafe.backend.domain.logs.model.entity.FallLog
import com.onsafe.backend.domain.logs.repository.FallLogRepository
import com.onsafe.backend.domain.logs.service.FallLogVideoReconciliationJob
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

class FallLogVideoReconciliationJobTest {

    private val fallLogRepository: FallLogRepository = mockk()
    private val storageService: StorageService = mockk()
    private lateinit var job: FallLogVideoReconciliationJob

    private fun log(logId: String, userId: String, minutesAgo: Long, score: Float = 90f, fall: Boolean = false) = FallLog(
        logId = logId,
        deviceId = "device1",
        userId = userId,
        score = score,
        fall = fall,
        isConfirmed = false,
        videoUrl = null,
        timestamp = LocalDateTime.now().minusMinutes(minutesAgo)
    )

    @BeforeEach
    fun setUp() {
        job = FallLogVideoReconciliationJob(fallLogRepository, storageService)
    }

    // ── grace period 이전 로그는 아직 업로드 중일 수 있어 건너뜀 ──────────────

    @Test
    fun `grace period(15분) 이전 최근 로그는 건드리지 않는다`() {
        val tooRecent = log("recent", "userA", minutesAgo = 5)

        coEvery { fallLogRepository.findMissingVideoLogs(any()) } returns listOf(tooRecent)

        job.reconcile()

        coVerify(exactly = 0) { storageService.blobExists(any()) }
    }

    // ── GCS에 실제 객체가 있으면 video_url 반영 (콜백 유실 보정) ───────────────

    @Test
    fun `GCS에 객체가 존재하면 video_url을 반영한다`() {
        val candidate = log("log1", "userB", minutesAgo = 30)

        coEvery { fallLogRepository.findMissingVideoLogs(any()) } returns listOf(candidate)
        coEvery { storageService.blobExists("fall-videos/log1.mp4") } returns true
        coEvery { fallLogRepository.setVideoUrlByLogIdAndUserId("log1", "userB", "fall-videos/log1.mp4") } returns
            candidate.copy(videoUrl = "fall-videos/log1.mp4")

        job.reconcile()

        coVerify(exactly = 1) { fallLogRepository.setVideoUrlByLogIdAndUserId("log1", "userB", "fall-videos/log1.mp4") }
    }

    // ── GCS에 아직 객체가 없으면 건드리지 않음 — 다음 실행에서 재시도 ───────────

    @Test
    fun `GCS에 객체가 없으면 video_url을 갱신하지 않는다`() {
        val candidate = log("log2", "userC", minutesAgo = 30)

        coEvery { fallLogRepository.findMissingVideoLogs(any()) } returns listOf(candidate)
        coEvery { storageService.blobExists("fall-videos/log2.mp4") } returns false

        job.reconcile()

        coVerify(exactly = 0) { fallLogRepository.setVideoUrlByLogIdAndUserId(any(), any(), any()) }
    }

    // ── 여러 후보는 각각 독립적으로 처리 ────────────────────────────────

    @Test
    fun `여러 후보는 각각 GCS 존재 여부를 확인한다`() {
        val a = log("a1", "userA", minutesAgo = 30)
        val b = log("b1", "userB", minutesAgo = 30)

        coEvery { fallLogRepository.findMissingVideoLogs(any()) } returns listOf(a, b)
        coEvery { storageService.blobExists("fall-videos/a1.mp4") } returns true
        coEvery { storageService.blobExists("fall-videos/b1.mp4") } returns false
        coEvery { fallLogRepository.setVideoUrlByLogIdAndUserId("a1", "userA", "fall-videos/a1.mp4") } returns
            a.copy(videoUrl = "fall-videos/a1.mp4")

        job.reconcile()

        coVerify(exactly = 1) { fallLogRepository.setVideoUrlByLogIdAndUserId("a1", "userA", any()) }
        coVerify(exactly = 0) { fallLogRepository.setVideoUrlByLogIdAndUserId("b1", "userB", any()) }
    }
}
