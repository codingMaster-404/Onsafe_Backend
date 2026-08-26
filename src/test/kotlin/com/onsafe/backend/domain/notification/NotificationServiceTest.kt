package com.onsafe.backend.domain.notification

import com.google.api.core.ApiFuture
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.Message
import com.onsafe.backend.common.exception.BusinessException
import com.onsafe.backend.common.exception.ErrorCode
import com.onsafe.backend.domain.guardian.repository.GuardianLinkRepository
import com.onsafe.backend.domain.notification.model.dto.NotificationRequest
import com.onsafe.backend.domain.notification.repository.NotificationRepository
import com.onsafe.backend.domain.notification.service.NotificationService
import com.onsafe.backend.domain.user.model.entity.User
import com.onsafe.backend.domain.user.repository.UserRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

class NotificationServiceTest {

    private val userRepository: UserRepository = mockk()
    private val notificationRepository: NotificationRepository = mockk()
    private val guardianLinkRepository: GuardianLinkRepository = mockk()
    private lateinit var notificationService: NotificationService

    private val baseUser = User(
        userId = "testUser",
        password = "encoded",
        name = "홍길동",
        phone = "010-1234-5678",
        mail = "test@example.com",
        fcmToken = "valid-fcm-token",
        createdAt = LocalDateTime.now()
    )

    @BeforeEach
    fun setUp() {
        // 알림 기록은 FCM 발송 성공/실패와 무관하게 항상 남으므로 기본 저장 스텁만 걸어둔다.
        coEvery { notificationRepository.save(any()) } answers { firstArg() }
        notificationService = NotificationService(userRepository, notificationRepository, guardianLinkRepository)
        mockkStatic(FirebaseMessaging::class)
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(FirebaseMessaging::class)
    }

    // ── USER_NOT_FOUND ────────────────────────────────────────────

    @Test
    fun `존재하지 않는 유저면 USER_NOT_FOUND 예외 발생`() = runTest {
        coEvery { userRepository.findByUserId("unknown") } returns null

        val thrown = runCatching {
            notificationService.sendNotification(
                NotificationRequest(userId = "unknown", title = "낙상 경보", body = "낙상 감지")
            )
        }.exceptionOrNull()

        assertTrue(thrown is BusinessException)
        assertEquals(ErrorCode.USER_NOT_FOUND, (thrown as BusinessException).errorCode)
    }

    // ── FCM 토큰 없음 ─────────────────────────────────────────────

    @Test
    fun `FCM 토큰 없으면 예외 없이 ok 반환`() = runTest {
        coEvery { userRepository.findByUserId("testUser") } returns baseUser.copy(fcmToken = null)

        val result = notificationService.sendNotification(
            NotificationRequest(userId = "testUser", title = "낙상 경보", body = "낙상 감지")
        )

        assertEquals("ok", result.status)
        assertEquals("FCM 토큰이 없습니다.", result.message)
        assertEquals("", result.fcmMessageId)
    }

    // ── FCM 전송 성공 ─────────────────────────────────────────────

    @Test
    fun `FCM 전송 성공 시 ok 상태와 messageId 반환`() = runTest {
        coEvery { userRepository.findByUserId("testUser") } returns baseUser

        val mockFcm: FirebaseMessaging = mockk()
        val mockFuture: ApiFuture<String> = mockk()
        every { FirebaseMessaging.getInstance() } returns mockFcm
        every { mockFcm.sendAsync(any<Message>()) } returns mockFuture
        every { mockFuture.get() } returns "projects/onsafe/messages/abc123"

        val result = notificationService.sendNotification(
            NotificationRequest(userId = "testUser", title = "낙상 경보", body = "낙상 감지")
        )

        assertEquals("ok", result.status)
        assertEquals("알림 전송 완료", result.message)
        assertEquals("projects/onsafe/messages/abc123", result.fcmMessageId)
    }

    // ── FCM 전송 실패 ─────────────────────────────────────────────

    @Test
    fun `FCM 전송 실패 시 FCM_SEND_FAILED 예외 발생`() = runTest {
        coEvery { userRepository.findByUserId("testUser") } returns baseUser

        val mockFcm: FirebaseMessaging = mockk()
        val mockFuture: ApiFuture<String> = mockk()
        every { FirebaseMessaging.getInstance() } returns mockFcm
        every { mockFcm.sendAsync(any<Message>()) } returns mockFuture
        every { mockFuture.get() } throws RuntimeException("FCM connection failed")

        val thrown = runCatching {
            notificationService.sendNotification(
                NotificationRequest(userId = "testUser", title = "낙상 경보", body = "낙상 감지")
            )
        }.exceptionOrNull()

        assertTrue(thrown is BusinessException)
        assertEquals(ErrorCode.FCM_SEND_FAILED, (thrown as BusinessException).errorCode)
    }
}
