package com.onsafe.backend.domain.logs

import com.onsafe.backend.common.exception.BusinessException
import com.onsafe.backend.common.exception.ErrorCode
import com.onsafe.backend.common.storage.StorageService
import com.onsafe.backend.domain.logs.model.entity.FallLog
import com.onsafe.backend.domain.logs.repository.FallLogRepository
import com.onsafe.backend.domain.logs.service.FallLogService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
class FallLogServiceTest {

    private val fallLogRepository: FallLogRepository = mockk()
    private val storageService: StorageService = mockk()
    private lateinit var fallLogService: FallLogService

    private val baseLog = FallLog(
        logId = "log001",
        deviceId = "device1",
        userId = "testUser",
        score = 85f,
        fall = true,
        isConfirmed = false,
        videoUrl = "gs://bucket/clip.mp4"
    )

    @BeforeEach
    fun setUp() {
        fallLogService = FallLogService(fallLogRepository, storageService)
    }

    // ── 단건 조회 ─────────────────────────────────────────────────

    @Test
    fun `사고 이력 단건 조회 - 존재하지 않으면 LOG_NOT_FOUND 예외 발생`() = runTest {
        coEvery { fallLogRepository.findByLogIdAndUserId("log999", "testUser") } returns null

        val thrown = runCatching {
            fallLogService.getLog("testUser", "log999")
        }.exceptionOrNull()

        assertTrue(thrown is BusinessException)
        assertEquals(ErrorCode.LOG_NOT_FOUND, (thrown as BusinessException).errorCode)
    }

    // ── 확인 처리 ─────────────────────────────────────────────────

    @Test
    fun `사고 이력 확인 처리 - 존재하지 않으면 LOG_NOT_FOUND 예외 발생`() = runTest {
        coEvery { fallLogRepository.confirmByLogIdAndUserId("log999", "testUser") } returns null

        val thrown = runCatching {
            fallLogService.confirmLog("testUser", "log999")
        }.exceptionOrNull()

        assertTrue(thrown is BusinessException)
        assertEquals(ErrorCode.LOG_NOT_FOUND, (thrown as BusinessException).errorCode)
    }

    // ── 삭제 ─────────────────────────────────────────────────────

    @Test
    fun `사고 이력 삭제 - 존재하지 않으면 LOG_NOT_FOUND 예외 발생`() = runTest {
        coEvery { fallLogRepository.deleteByLogIdAndUserId("log999", "testUser") } returns 0L

        val thrown = runCatching {
            fallLogService.deleteLog("testUser", "log999")
        }.exceptionOrNull()

        assertTrue(thrown is BusinessException)
        assertEquals(ErrorCode.LOG_NOT_FOUND, (thrown as BusinessException).errorCode)
    }

    // ── 동영상 서명 URL 조회 ──────────────────────────────────────

    @Test
    fun `동영상 URL 조회 - 로그 없으면 LOG_NOT_FOUND 예외 발생`() = runTest {
        coEvery { fallLogRepository.findByLogIdAndUserId("log999", "testUser") } returns null

        val thrown = runCatching {
            fallLogService.getSignedUrl("testUser", "log999")
        }.exceptionOrNull()

        assertTrue(thrown is BusinessException)
        assertEquals(ErrorCode.LOG_NOT_FOUND, (thrown as BusinessException).errorCode)
    }

    @Test
    fun `동영상 URL 조회 - 동영상 없는 로그면 VIDEO_NOT_FOUND 예외 발생`() = runTest {
        coEvery { fallLogRepository.findByLogIdAndUserId("log001", "testUser") } returns baseLog.copy(videoUrl = null)

        val thrown = runCatching {
            fallLogService.getSignedUrl("testUser", "log001")
        }.exceptionOrNull()

        assertTrue(thrown is BusinessException)
        assertEquals(ErrorCode.VIDEO_NOT_FOUND, (thrown as BusinessException).errorCode)
    }

    // ── 업로드용 서명 URL 발급 (#14) ─────────────────────────────────

    @Test
    fun `업로드 URL 발급 - 로그 없으면 LOG_NOT_FOUND 예외 발생`() = runTest {
        coEvery { fallLogRepository.findByLogIdAndUserId("log999", "testUser") } returns null

        val thrown = runCatching {
            fallLogService.getUploadUrl("testUser", "log999")
        }.exceptionOrNull()

        assertTrue(thrown is BusinessException)
        assertEquals(ErrorCode.LOG_NOT_FOUND, (thrown as BusinessException).errorCode)
    }

    @Test
    fun `업로드 URL 발급 - 로그 존재 시 fall-videos 경로로 signed PUT URL 요청`() = runTest {
        coEvery { fallLogRepository.findByLogIdAndUserId("log001", "testUser") } returns baseLog
        coEvery { storageService.generateSignedUploadUrl("fall-videos/log001.mp4") } returns "https://signed.example/upload"

        val result = fallLogService.getUploadUrl("testUser", "log001")

        assertEquals("https://signed.example/upload", result)
    }

    @Test
    fun `업로드 URL 발급 - 주의 등급 로그면 VIDEO_NOT_ALLOWED 예외 발생 (영상은 위험 등급만 제공)`() = runTest {
        val cautionLog = baseLog.copy(score = 60f, fall = false)
        coEvery { fallLogRepository.findByLogIdAndUserId("log001", "testUser") } returns cautionLog

        val thrown = runCatching {
            fallLogService.getUploadUrl("testUser", "log001")
        }.exceptionOrNull()

        assertTrue(thrown is BusinessException)
        assertEquals(ErrorCode.VIDEO_NOT_ALLOWED, (thrown as BusinessException).errorCode)
    }

    // ── 업로드 완료 콜백 (#15) ────────────────────────────────────────

    @Test
    fun `업로드 완료 콜백 - 로그 없으면 LOG_NOT_FOUND 예외 발생`() = runTest {
        coEvery { fallLogRepository.findByLogIdAndUserId("log999", "testUser") } returns null

        val thrown = runCatching {
            fallLogService.completeVideoUpload("testUser", "log999")
        }.exceptionOrNull()

        assertTrue(thrown is BusinessException)
        assertEquals(ErrorCode.LOG_NOT_FOUND, (thrown as BusinessException).errorCode)
    }

    @Test
    fun `업로드 완료 콜백 - GCS에 객체가 없으면 VIDEO_NOT_FOUND 예외 발생 (클라이언트 주장만으로 반영 안 함)`() = runTest {
        coEvery { fallLogRepository.findByLogIdAndUserId("log001", "testUser") } returns baseLog.copy(videoUrl = null)
        coEvery { storageService.blobExists("fall-videos/log001.mp4") } returns false

        val thrown = runCatching {
            fallLogService.completeVideoUpload("testUser", "log001")
        }.exceptionOrNull()

        assertTrue(thrown is BusinessException)
        assertEquals(ErrorCode.VIDEO_NOT_FOUND, (thrown as BusinessException).errorCode)
    }

    @Test
    fun `업로드 완료 콜백 - 주의 등급 로그면 GCS 확인 없이 VIDEO_NOT_ALLOWED 예외 발생`() = runTest {
        val cautionLog = baseLog.copy(score = 55f, fall = false, videoUrl = null)
        coEvery { fallLogRepository.findByLogIdAndUserId("log001", "testUser") } returns cautionLog

        val thrown = runCatching {
            fallLogService.completeVideoUpload("testUser", "log001")
        }.exceptionOrNull()

        assertTrue(thrown is BusinessException)
        assertEquals(ErrorCode.VIDEO_NOT_ALLOWED, (thrown as BusinessException).errorCode)
        coVerify(exactly = 0) { storageService.blobExists(any()) }
    }

    @Test
    fun `업로드 완료 콜백 - GCS에 객체 존재 확인되면 video_url 반영`() = runTest {
        val logBeforeUpload = baseLog.copy(videoUrl = null)
        coEvery { fallLogRepository.findByLogIdAndUserId("log001", "testUser") } returns logBeforeUpload
        coEvery { storageService.blobExists("fall-videos/log001.mp4") } returns true
        coEvery {
            fallLogRepository.setVideoUrlByLogIdAndUserId("log001", "testUser", "fall-videos/log001.mp4")
        } returns logBeforeUpload.copy(videoUrl = "fall-videos/log001.mp4")

        val result = fallLogService.completeVideoUpload("testUser", "log001")

        assertTrue(result.hasVideo)
    }
}
