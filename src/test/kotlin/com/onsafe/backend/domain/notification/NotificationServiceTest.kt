package com.onsafe.backend.domain.notification

import com.google.api.core.ApiFuture
import com.google.firebase.messaging.BatchResponse
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingException
import com.google.firebase.messaging.MessagingErrorCode
import com.google.firebase.messaging.MulticastMessage
import com.google.firebase.messaging.SendResponse
import com.google.firebase.messaging.Message
import com.onsafe.backend.common.exception.BusinessException
import com.onsafe.backend.common.exception.ErrorCode
import com.onsafe.backend.domain.guardian.repository.GuardianLinkRepository
import com.onsafe.backend.domain.notification.model.dto.NotificationRequest
import com.onsafe.backend.domain.notification.model.entity.FcmToken
import com.onsafe.backend.domain.notification.repository.FcmTokenRepository
import com.onsafe.backend.domain.notification.repository.NotificationRepository
import com.onsafe.backend.domain.notification.service.NotificationService
import com.onsafe.backend.domain.settings.model.entity.UserSettings
import com.onsafe.backend.domain.settings.repository.SettingsRepository
import com.onsafe.backend.domain.user.model.entity.User
import com.onsafe.backend.domain.user.repository.UserRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.coVerify
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
    private val fcmTokenRepository: FcmTokenRepository = mockk(relaxUnitFun = true)
    private val settingsRepository: SettingsRepository = mockk()
    private lateinit var notificationService: NotificationService

    private val baseUser = User(
        userId = "testUser",
        password = "encoded",
        name = "홍길동",
        phone = "010-1234-5678",
        mail = "test@example.com",
        createdAt = LocalDateTime.now()
    )

    @BeforeEach
    fun setUp() {
        // 알림 기록은 FCM 발송 성공/실패와 무관하게 항상 남으므로 기본 저장 스텁만 걸어둔다.
        coEvery { notificationRepository.save(any()) } answers { firstArg() }
        // 알림 ON이 기본값이다. 설정을 끄는 케이스만 각 테스트에서 이 스텁을 덮어쓴다.
        coEvery { settingsRepository.findByUserId(any()) } returns UserSettings("testUser")
        notificationService = NotificationService(
            userRepository, notificationRepository, guardianLinkRepository, fcmTokenRepository,
            settingsRepository
        )
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
        coEvery { userRepository.findByUserId("testUser") } returns baseUser
        coEvery { fcmTokenRepository.findAll("testUser") } returns emptyList()

        val result = notificationService.sendNotification(
            NotificationRequest(userId = "testUser", title = "낙상 경보", body = "낙상 감지")
        )

        assertEquals("ok", result.status)
        assertEquals("FCM 토큰이 없습니다.", result.message)
        assertEquals("", result.fcmMessageId)
    }

    // ── FCM 전송 (기기별 다중 토큰) ───────────────────────────────

    private fun tokens(vararg ids: String) = ids.map { FcmToken(deviceId = it, token = "token-$it") }

    // sendEachForMulticastAsync 결과를 토큰별로 흉내 낸다. success=true면 성공, 아니면 UNREGISTERED.
    private fun stubMulticast(vararg successes: Boolean): BatchResponse {
        val mockFcm: FirebaseMessaging = mockk()
        val future: ApiFuture<BatchResponse> = mockk()
        val batch: BatchResponse = mockk()
        val responses = successes.mapIndexed { index, ok ->
            mockk<SendResponse>().also {
                every { it.isSuccessful } returns ok
                every { it.messageId } returns if (ok) "projects/onsafe/messages/msg$index" else null
                every { it.exception } returns if (ok) null else mockk<FirebaseMessagingException>().also { e ->
                    every { e.messagingErrorCode } returns MessagingErrorCode.UNREGISTERED
                }
            }
        }
        every { batch.responses } returns responses
        every { batch.successCount } returns successes.count { it }
        every { FirebaseMessaging.getInstance() } returns mockFcm
        every { mockFcm.sendEachForMulticastAsync(any<MulticastMessage>()) } returns future
        every { future.get() } returns batch
        return batch
    }

    @Test
    fun `등록된 기기 전부에 발송하고 messageId를 돌려준다 (B8)`() = runTest {
        coEvery { userRepository.findByUserId("testUser") } returns baseUser
        coEvery { fcmTokenRepository.findAll("testUser") } returns tokens("phone", "camera")
        stubMulticast(true, true)

        val result = notificationService.sendNotification(
            NotificationRequest(userId = "testUser", title = "낙상 경보", body = "낙상 감지")
        )

        assertEquals("ok", result.status)
        assertEquals("알림 전송 완료 (2/2)", result.message)
        assertEquals("projects/onsafe/messages/msg0", result.fcmMessageId)
    }

    @Test
    fun `일부 토큰만 만료면 그 기기 토큰만 지우고 나머지는 정상 발송`() = runTest {
        coEvery { userRepository.findByUserId("testUser") } returns baseUser
        coEvery { fcmTokenRepository.findAll("testUser") } returns tokens("phone", "old")
        stubMulticast(true, false)

        val result = notificationService.sendNotification(
            NotificationRequest(userId = "testUser", title = "낙상 경보", body = "낙상 감지")
        )

        assertEquals("ok", result.status)
        assertEquals("알림 전송 완료 (1/2)", result.message)
        // 예전 구조에서는 실패 한 번에 계정의 유일한 토큰이 지워져 전면 미수신이 됐다.
        coVerify(exactly = 1) { fcmTokenRepository.deleteByToken("testUser", "token-old") }
        coVerify(exactly = 0) { fcmTokenRepository.deleteByToken("testUser", "token-phone") }
    }

    @Test
    fun `모든 토큰이 실패하면 FCM_SEND_FAILED 예외 발생`() = runTest {
        coEvery { userRepository.findByUserId("testUser") } returns baseUser
        coEvery { fcmTokenRepository.findAll("testUser") } returns tokens("phone")
        stubMulticast(false)

        val thrown = runCatching {
            notificationService.sendNotification(
                NotificationRequest(userId = "testUser", title = "낙상 경보", body = "낙상 감지")
            )
        }.exceptionOrNull()

        assertTrue(thrown is BusinessException)
        assertEquals(ErrorCode.FCM_SEND_FAILED, (thrown as BusinessException).errorCode)
    }

    @Test
    fun `FCM 호출 자체가 실패하면 FCM_SEND_FAILED 예외 발생`() = runTest {
        coEvery { userRepository.findByUserId("testUser") } returns baseUser
        coEvery { fcmTokenRepository.findAll("testUser") } returns tokens("phone")
        val mockFcm: FirebaseMessaging = mockk()
        val future: ApiFuture<BatchResponse> = mockk()
        every { FirebaseMessaging.getInstance() } returns mockFcm
        every { mockFcm.sendEachForMulticastAsync(any<MulticastMessage>()) } returns future
        every { future.get() } throws RuntimeException("FCM connection failed")

        val thrown = runCatching {
            notificationService.sendNotification(
                NotificationRequest(userId = "testUser", title = "낙상 경보", body = "낙상 감지")
            )
        }.exceptionOrNull()

        assertEquals(ErrorCode.FCM_SEND_FAILED, (thrown as BusinessException).errorCode)
    }

    // ── 토큰 등록·해제 ────────────────────────────────────────────

    @Test
    fun `토큰 등록 - 존재하지 않는 유저면 USER_NOT_FOUND`() = runTest {
        coEvery { userRepository.findByUserId("unknown") } returns null

        val thrown = runCatching {
            notificationService.registerFcmToken("unknown", "t", "device1")
        }.exceptionOrNull()

        assertEquals(ErrorCode.USER_NOT_FOUND, (thrown as BusinessException).errorCode)
    }

    @Test
    fun `토큰 등록 - 기기 단위로 저장한다`() = runTest {
        coEvery { userRepository.findByUserId("testUser") } returns baseUser

        notificationService.registerFcmToken("testUser", "token-phone", "device1")

        coVerify(exactly = 1) { fcmTokenRepository.upsert("testUser", "device1", "token-phone") }
    }

    @Test
    fun `토큰 해제 - 해당 기기 토큰만 지운다`() = runTest {
        notificationService.unregisterFcmToken("testUser", "token-phone", "device1")

        coVerify(exactly = 1) { fcmTokenRepository.delete("testUser", "device1", "token-phone") }
    }

    // ── 사용자 알림 설정 존중 ─────────────────────────

    @Test
    fun `알림을 끄면 FCM은 건너뛰지만 알림 기록은 남긴다`() = runTest {
        coEvery { userRepository.findByUserId("testUser") } returns baseUser
        coEvery { settingsRepository.findByUserId("testUser") } returns
            UserSettings("testUser", notificationEnabled = false)

        val result = notificationService.sendNotification(
            NotificationRequest(userId = "testUser", title = "보호자 연결", body = "연결되었습니다")
        )

        assertEquals("ok", result.status)
        assertEquals("사용자가 알림을 비활성화했습니다.", result.message)
        assertEquals("", result.fcmMessageId)
        // 알림함에서는 보여야 하므로 저장은 그대로 수행된다.
        coVerify(exactly = 1) { notificationRepository.save(any()) }
        // 발송하지 않을 사용자라 토큰 서브컴렉션 조회 자체를 건너뛴다.
        coVerify(exactly = 0) { fcmTokenRepository.findAll(any()) }
    }

    @Test
    fun `알림을 꺼도 낙상은 강제 발송한다`() = runTest {
        coEvery { userRepository.findByUserId("testUser") } returns baseUser
        coEvery { settingsRepository.findByUserId("testUser") } returns
            UserSettings("testUser", notificationEnabled = false)
        coEvery { fcmTokenRepository.findAll("testUser") } returns tokens("phone")
        stubMulticast(true)

        val result = notificationService.sendNotification(
            NotificationRequest(userId = "testUser", title = "낙상 경보", body = "낙상 감지", fall = true)
        )

        assertEquals("알림 전송 완료 (1/1)", result.message)
    }

    @Test
    fun `알림을 꺼도 위험 임계를 넘기면 강제 발송한다`() = runTest {
        coEvery { userRepository.findByUserId("testUser") } returns baseUser
        coEvery { settingsRepository.findByUserId("testUser") } returns
            UserSettings("testUser", notificationEnabled = false)
        coEvery { fcmTokenRepository.findAll("testUser") } returns tokens("phone")
        stubMulticast(true)

        val result = notificationService.sendNotification(
            NotificationRequest(userId = "testUser", title = "위험", body = "위험 감지", score = 80f)
        )

        assertEquals("알림 전송 완료 (1/1)", result.message)
    }

    @Test
    fun `설정 조회가 실패하면 기본값 true로 발송한다`() = runTest {
        coEvery { userRepository.findByUserId("testUser") } returns baseUser
        coEvery { settingsRepository.findByUserId("testUser") } throws RuntimeException("firestore down")
        coEvery { fcmTokenRepository.findAll("testUser") } returns tokens("phone")
        stubMulticast(true)

        val result = notificationService.sendNotification(
            NotificationRequest(userId = "testUser", title = "보호자 연결", body = "연결되었습니다")
        )

        // 조회 오류 하나로 알림이 전면 차단되면 안 된다.
        assertEquals("알림 전송 완료 (1/1)", result.message)
    }
}
