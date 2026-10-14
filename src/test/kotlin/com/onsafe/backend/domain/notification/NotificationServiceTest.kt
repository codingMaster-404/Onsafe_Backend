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
import com.onsafe.backend.domain.logs.model.entity.FallLog
import com.onsafe.backend.domain.logs.repository.FallLogRepository
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
import io.mockk.verify
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
    private val fallLogRepository: FallLogRepository = mockk()
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
            settingsRepository, fallLogRepository
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

    // ── data 전용 메시지 (실시간 영상 송출 요청) ─────────────────

    @Test
    fun `data 메시지 - 알림함에 저장하지 않고 알림 설정과 무관하게 보내며 성공 기기 수를 돌려준다`() = runTest {
        coEvery { fcmTokenRepository.findAll("testUser") } returns tokens("camera")
        coEvery { settingsRepository.findByUserId("testUser") } returns UserSettings("testUser", notificationEnabled = false)
        stubMulticast(true)

        val delivered = notificationService.sendDataMessage(
            "testUser", mapOf("event" to "live_request"), java.time.Duration.ofMinutes(5)
        )

        assertEquals(1, delivered)
        coVerify(exactly = 0) { notificationRepository.save(any()) }
        coVerify(exactly = 0) { settingsRepository.findByUserId(any()) }
    }

    @Test
    fun `data 메시지 - 만료된 토큰은 sendNotification과 같이 지운다`() = runTest {
        coEvery { fcmTokenRepository.findAll("testUser") } returns tokens("camera", "old")
        stubMulticast(true, false)

        val delivered = notificationService.sendDataMessage(
            "testUser", mapOf("event" to "live_request"), java.time.Duration.ofMinutes(5)
        )

        assertEquals(1, delivered)
        coVerify(exactly = 1) { fcmTokenRepository.deleteByToken("testUser", "token-old") }
    }

    @Test
    fun `data 메시지 - 토큰이 없으면 보내지 않고 0`() = runTest {
        coEvery { fcmTokenRepository.findAll("testUser") } returns emptyList()

        val delivered = notificationService.sendDataMessage(
            "testUser", mapOf("event" to "live_request"), java.time.Duration.ofMinutes(5)
        )

        assertEquals(0, delivered)
        verify(exactly = 0) { FirebaseMessaging.getInstance() }
    }

    // ── 감지 중단·재개 알림(카메라 스트리밍 B5) ─────────────────────

    private fun stubGuardians(vararg guardianIds: String) {
        coEvery { userRepository.findByUserId("testUser") } returns baseUser
        coEvery { guardianLinkRepository.findGuardiansOf("testUser") } returns guardianIds.toList()
        guardianIds.forEach { id ->
            coEvery { userRepository.findByUserId(id) } returns baseUser.copy(userId = id, name = "보호자")
            coEvery { fcmTokenRepository.findAll(id) } returns emptyList()
        }
    }

    @Test
    fun `감지 중단 알림 - 보호자 전원에게 피보호자 이름으로 보낸다`() = runTest {
        stubGuardians("g1", "g2")

        notificationService.notifyGuardiansStreamDown("testUser")

        listOf("g1", "g2").forEach { id ->
            coVerify(exactly = 1) {
                notificationRepository.save(match {
                    it.userId == id && it.title == "낙상 감지 중단" && it.body.startsWith("[홍길동]")
                })
            }
        }
    }

    @Test
    fun `감지 재개 알림 - 보호자에게 보내고 보호자가 없으면 아무것도 보내지 않는다`() = runTest {
        stubGuardians("g1")
        notificationService.notifyGuardiansStreamRecovered("testUser")
        coVerify(exactly = 1) { notificationRepository.save(match { it.userId == "g1" && it.title == "낙상 감지 재개" }) }

        coEvery { guardianLinkRepository.findGuardiansOf("lonely") } returns emptyList()
        coEvery { userRepository.findByUserId("lonely") } returns baseUser.copy(userId = "lonely")
        notificationService.notifyGuardiansStreamRecovered("lonely")
        coVerify(exactly = 1) { notificationRepository.save(any()) }
    }

    // ── 알림 대상 피보호자·알림함 정리 (S1) ─────────────────────

    @Test
    fun `낙상 알림 - 본인·보호자 사본 모두 대상 피보호자를 남긴다`() = runTest {
        coEvery { userRepository.findByUserId("testUser") } returns baseUser
        coEvery { userRepository.findByUserId("g1") } returns baseUser.copy(userId = "g1")
        coEvery { guardianLinkRepository.findGuardiansOf("testUser") } returns listOf("g1")
        coEvery { fcmTokenRepository.findAll(any()) } returns emptyList()

        notificationService.notifyElderAndGuardians("testUser", "낙상 감지", "확인해주세요", logId = "log1", fall = true)

        listOf("testUser", "g1").forEach { id ->
            coVerify(exactly = 1) { notificationRepository.save(match { it.userId == id && it.subjectUserId == "testUser" }) }
        }
    }

    @Test
    fun `오프라인·복구 알림 - 보호자 사본에 대상 피보호자를 남긴다`() = runTest {
        coEvery { userRepository.findByUserId("testUser") } returns baseUser
        coEvery { userRepository.findByUserId("g1") } returns baseUser.copy(userId = "g1")
        coEvery { guardianLinkRepository.findGuardiansOf("testUser") } returns listOf("g1")
        coEvery { fcmTokenRepository.findAll(any()) } returns emptyList()

        notificationService.notifyGuardiansCameraOffline("testUser")
        notificationService.notifyGuardiansCameraRecovered("testUser")

        coVerify(exactly = 2) { notificationRepository.save(match { it.userId == "g1" && it.subjectUserId == "testUser" }) }
    }

    @Test
    fun `알림함 정리 - 대상 필드로 지우고 필드 없는 옛 문서는 최근 낙상 log_id로 보호자 것만 지운다`() = runTest {
        coEvery { notificationRepository.deleteByUserAndSubject("g1", "testUser") } returns 2
        coEvery { fallLogRepository.findRecentByUserId("testUser", since = any()) } returns listOf(
            FallLog(logId = "log1", deviceId = "d", userId = "testUser", score = 0.9f, fall = true),
            FallLog(logId = "log2", deviceId = "d", userId = "testUser", score = 0.5f, fall = false),
        )
        coEvery { notificationRepository.deleteByUserAndLogIds("g1", listOf("log1", "log2")) } returns 1

        notificationService.purgeGuardianInbox("g1", "testUser")

        coVerify(exactly = 1) { notificationRepository.deleteByUserAndSubject("g1", "testUser") }
        coVerify(exactly = 1) { notificationRepository.deleteByUserAndLogIds("g1", listOf("log1", "log2")) }
        coVerify(exactly = 0) { notificationRepository.deleteByLogIds(any()) }  // 본인 사본까지 지우는 탈퇴용 경로는 쓰지 않는다
    }

    // ── 보호자 사본 본문 대체 (S6) ─────────────────────────────

    @Test
    fun `guardianBody - 보호자 사본만 바꾸고 이름 접두는 유지하며 본인 사본은 원래 본문`() = runTest {
        coEvery { userRepository.findByUserId("testUser") } returns baseUser
        coEvery { userRepository.findByUserId("g1") } returns baseUser.copy(userId = "g1")
        coEvery { guardianLinkRepository.findGuardiansOf("testUser") } returns listOf("g1")
        coEvery { fcmTokenRepository.findAll(any()) } returns emptyList()

        notificationService.notifyElderAndGuardians("testUser", "확인이 필요합니다", "원래 본문", guardianBody = "보호자 본문")

        coVerify(exactly = 1) { notificationRepository.save(match { it.userId == "testUser" && it.body == "원래 본문" }) }
        coVerify(exactly = 1) { notificationRepository.save(match { it.userId == "g1" && it.body == "[홍길동] 보호자 본문" }) }
    }
}
