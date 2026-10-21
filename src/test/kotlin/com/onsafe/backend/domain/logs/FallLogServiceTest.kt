package com.onsafe.backend.domain.logs

import com.onsafe.backend.common.exception.BusinessException
import com.onsafe.backend.common.exception.ErrorCode
import com.onsafe.backend.common.storage.StorageService
import com.onsafe.backend.domain.auth.model.entity.LoginHistory
import com.onsafe.backend.domain.auth.model.entity.SecurityEventType
import com.onsafe.backend.domain.auth.repository.LoginHistoryRepository
import com.onsafe.backend.domain.logs.model.entity.FallLog
import com.onsafe.backend.domain.logs.repository.FallLogRepository
import com.onsafe.backend.domain.logs.service.FallLogService
import com.onsafe.backend.domain.notification.repository.NotificationRepository
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
class FallLogServiceTest {

    private val fallLogRepository: FallLogRepository = mockk()
    private val storageService: StorageService = mockk()
    private val notificationRepository: NotificationRepository = mockk()
    private val loginHistoryRepository: LoginHistoryRepository = mockk()
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
        fallLogService = FallLogService(fallLogRepository, storageService, notificationRepository, loginHistoryRepository)
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
        coEvery { fallLogRepository.findByLogIdAndUserId("log999", "testUser", null) } returns null

        val thrown = runCatching {
            fallLogService.deleteLog("testUser", "log999")
        }.exceptionOrNull()

        assertTrue(thrown is BusinessException)
        assertEquals(ErrorCode.LOG_NOT_FOUND, (thrown as BusinessException).errorCode)
        coVerify(exactly = 0) { fallLogRepository.deleteByLogId(any()) }
    }

    private val linkedAt = LocalDateTime.of(2026, 10, 1, 0, 0)

    private fun givenDeletable(log: FallLog, since: LocalDateTime? = null): MutableList<LoginHistory> {
        coEvery { fallLogRepository.findByLogIdAndUserId(log.logId, "testUser", since) } returns log
        coJustRun { fallLogRepository.deleteByLogId(log.logId) }
        coEvery { storageService.deleteBlob("fall-videos/${log.logId}.mp4") } returns true
        coJustRun { notificationRepository.deleteByLogIds(listOf(log.logId)) }
        val events = mutableListOf<LoginHistory>()
        coEvery { loginHistoryRepository.save(capture(events)) } answers { firstArg() }
        return events
    }

    @Test
    fun `사고 이력 삭제 - 본인 삭제는 문서·영상·알림 사본을 지우고 보안 이벤트는 남기지 않는다`() = runTest {
        val events = givenDeletable(baseLog)

        fallLogService.deleteLog("testUser", "log001")

        coVerify(exactly = 1) { fallLogRepository.deleteByLogId("log001") }
        coVerify(exactly = 1) { storageService.deleteBlob("fall-videos/log001.mp4") }
        coVerify(exactly = 1) { notificationRepository.deleteByLogIds(listOf("log001")) }
        assertTrue(events.isEmpty())
    }

    @Test
    fun `사고 이력 삭제 - 본인은 확인하지 않은 위험 이벤트도 지울 수 있다`() = runTest {
        givenDeletable(baseLog.copy(isConfirmed = false))

        fallLogService.deleteLog("testUser", "log001", deletedBy = "testUser")

        coVerify(exactly = 1) { fallLogRepository.deleteByLogId("log001") }
    }

    @Test
    fun `사고 이력 삭제 - 보호자는 연결 이후 이력을 지우고 FALL_LOG_DELETED를 남긴다`() = runTest {
        val events = givenDeletable(baseLog.copy(isConfirmed = true), since = linkedAt)

        fallLogService.deleteLog("testUser", "log001", deletedBy = "guardian1", since = linkedAt)

        coVerify(exactly = 1) { fallLogRepository.deleteByLogId("log001") }
        coVerify(exactly = 1) { storageService.deleteBlob("fall-videos/log001.mp4") }
        // 명의는 보호자, 대상은 피보호자 — "누가 내 기록을 지웠나"
        assertEquals(SecurityEventType.FALL_LOG_DELETED, events.single().eventType)
        assertEquals("guardian1", events.single().userId)
        assertEquals("testUser", events.single().targetUserId)
    }

    @Test
    fun `사고 이력 삭제 - 보호자는 확인하지 않은 위험 이벤트를 지울 수 없다`() = runTest {
        givenDeletable(baseLog.copy(isConfirmed = false), since = linkedAt)

        val thrown = runCatching {
            fallLogService.deleteLog("testUser", "log001", deletedBy = "guardian1", since = linkedAt)
        }.exceptionOrNull()

        // 지우면 미확인 재알림이 멈춘다.
        assertEquals(ErrorCode.LOG_NOT_CONFIRMED, (thrown as BusinessException).errorCode)
        coVerify(exactly = 0) { fallLogRepository.deleteByLogId(any()) }
        coVerify(exactly = 0) { storageService.deleteBlob(any()) }
    }

    @Test
    fun `사고 이력 삭제 - 보호자는 확인하지 않았어도 주의 등급 이벤트는 지울 수 있다`() = runTest {
        givenDeletable(baseLog.copy(score = 40f, fall = false, isConfirmed = false), since = linkedAt)

        fallLogService.deleteLog("testUser", "log001", deletedBy = "guardian1", since = linkedAt)

        coVerify(exactly = 1) { fallLogRepository.deleteByLogId("log001") }
    }

    @Test
    fun `사고 이력 삭제 - 보호자에게 연결 이전 이력은 없는 것으로 보인다`() = runTest {
        coEvery { fallLogRepository.findByLogIdAndUserId("log001", "testUser", linkedAt) } returns null

        val thrown = runCatching {
            fallLogService.deleteLog("testUser", "log001", deletedBy = "guardian1", since = linkedAt)
        }.exceptionOrNull()

        assertEquals(ErrorCode.LOG_NOT_FOUND, (thrown as BusinessException).errorCode)
        coVerify(exactly = 0) { fallLogRepository.deleteByLogId(any()) }
    }

    @Test
    fun `사고 이력 삭제 - 영상·알림·기록 정리가 실패해도 삭제는 성공한다`() = runTest {
        givenDeletable(baseLog.copy(isConfirmed = true), since = linkedAt)
        coEvery { storageService.deleteBlob(any()) } throws RuntimeException("gcs down")
        coEvery { notificationRepository.deleteByLogIds(any()) } throws RuntimeException("firestore down")
        coEvery { loginHistoryRepository.save(any()) } throws RuntimeException("firestore down")

        fallLogService.deleteLog("testUser", "log001", deletedBy = "guardian1", since = linkedAt)

        coVerify(exactly = 1) { fallLogRepository.deleteByLogId("log001") }
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
