package com.onsafe.backend.domain.user

import com.onsafe.backend.common.exception.BusinessException
import com.onsafe.backend.common.exception.ErrorCode
import com.onsafe.backend.common.security.TokenRevocationStore
import com.onsafe.backend.common.storage.StorageService
import com.onsafe.backend.domain.auth.repository.LoginHistoryRepository
import com.onsafe.backend.domain.camera.repository.RealtimeDataRepository
import com.onsafe.backend.domain.consent.repository.ConsentRepository
import com.onsafe.backend.domain.guardian.repository.GuardianLinkRepository
import com.onsafe.backend.domain.logs.repository.FallLogRepository
import com.onsafe.backend.common.ratelimit.RateLimiter
import com.onsafe.backend.domain.auth.model.entity.LoginHistory
import com.onsafe.backend.domain.auth.model.entity.SecurityEventType
import com.onsafe.backend.domain.notification.repository.FcmTokenRepository
import com.onsafe.backend.domain.user.repository.DeletionTaskRepository
import com.onsafe.backend.domain.notification.repository.NotificationRepository
import com.onsafe.backend.domain.settings.repository.SettingsRepository
import com.onsafe.backend.domain.user.model.dto.UserUpdateRequest
import com.onsafe.backend.domain.user.model.entity.DeletionTask
import com.onsafe.backend.domain.user.model.entity.User
import com.onsafe.backend.domain.user.repository.UserRepository
import com.onsafe.backend.domain.user.service.UserService
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.data.redis.core.ReactiveValueOperations
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import reactor.core.publisher.Mono
import java.time.LocalDateTime

class UserServiceTest {

    private val userRepository: UserRepository = mockk()
    private val settingsRepository: SettingsRepository = mockk()
    private val fallLogRepository: FallLogRepository = mockk()
    private val loginHistoryRepository: LoginHistoryRepository = mockk()
    private val realtimeDataRepository: RealtimeDataRepository = mockk()
    private val storageService: StorageService = mockk()
    private val notificationRepository: NotificationRepository = mockk()
    private val guardianLinkRepository: GuardianLinkRepository = mockk()
    private val tokenRevocationStore: TokenRevocationStore = mockk()
    private val fcmTokenRepository: FcmTokenRepository = mockk(relaxUnitFun = true)
    private val rateLimiter: RateLimiter = mockk(relaxUnitFun = true)
    private val redis: ReactiveStringRedisTemplate = mockk()
    private val deletionTaskRepository: DeletionTaskRepository = mockk(relaxUnitFun = true)
    private val valueOps: ReactiveValueOperations<String, String> = mockk()
    private val consentRepository: ConsentRepository = mockk()
    private val passwordEncoder = BCryptPasswordEncoder()
    private lateinit var userService: UserService

    private val baseUser = User(
        userId = "testUser",
        password = BCryptPasswordEncoder().encode("oldPass"),
        name = "홍길동",
        phone = "010-1234-5678",
        mail = "test@test.com",
        address = null,
        addressDetail = null,
        createdAt = LocalDateTime.now()
    )

    @BeforeEach
    fun setUp() {
        coEvery { tokenRevocationStore.revokeAll(any()) } just Runs
        every { redis.opsForValue() } returns valueOps
        // 개인정보 수정은 재인증 티켓을 GETDEL로 소비한다(B1) — 기본은 유효한 티켓.
        every { valueOps.get("reauth_ticket:ticket-1") } returns Mono.just("testUser")
        every { redis.delete("reauth_ticket:ticket-1") } returns Mono.just(1L)
        // 저장은 메일·전화 룩업 이동까지 한 트랜잭션으로 한다(B1) — 기본은 성공.
        coEvery { userRepository.saveWithLookups(any(), any()) } returns true
        userService = UserService(
            userRepository, settingsRepository, passwordEncoder, fallLogRepository,
            loginHistoryRepository, realtimeDataRepository, storageService,
            notificationRepository, guardianLinkRepository, consentRepository,
            tokenRevocationStore,
            fcmTokenRepository,
            rateLimiter,
            redis,
            deletionTaskRepository
        )
    }

    // ── 세션 무효화 (A2·D6) ──────────────────────────────────────

    @Test
    fun `비밀번호 변경 - 성공 시 저장 전에 모든 세션을 무효화한다`() = runTest {
        coEvery { userRepository.findByUserId("testUser") } returns baseUser

        userService.updateUser("testUser", UserUpdateRequest(currentPassword = "oldPass", password = "newPass1234"))

        coVerifyOrder {
            tokenRevocationStore.revokeAll("testUser")
            userRepository.saveWithLookups(any(), any())
        }
    }

    @Test
    fun `비밀번호 변경 - 현재 비밀번호가 틀리면 세션을 무효화하지 않는다`() = runTest {
        coEvery { userRepository.findByUserId("testUser") } returns baseUser

        runCatching {
            userService.updateUser("testUser", UserUpdateRequest(currentPassword = "wrongPass", password = "newPass1234"))
        }

        coVerify(exactly = 0) { tokenRevocationStore.revokeAll(any()) }
    }

    @Test
    fun `주소만 수정하면 세션을 무효화하지 않는다`() = runTest {
        coEvery { userRepository.findByUserId("testUser") } returns baseUser

        userService.updateUser("testUser", UserUpdateRequest(address = "서울시 마포구", reauthTicket = "ticket-1"))

        coVerify(exactly = 0) { tokenRevocationStore.revokeAll(any()) }
    }

    @Test
    fun `회원 탈퇴 - 세션 무효화가 실패하면 아무 데이터도 지우지 않는다`() = runTest {
        coEvery { userRepository.findByUserId("testUser") } returns baseUser
        coEvery { tokenRevocationStore.revokeAll("testUser") } throws BusinessException(ErrorCode.REDIS_UNAVAILABLE)

        val thrown = runCatching { userService.deleteUser("testUser", "ticket-1") }.exceptionOrNull()

        assertEquals(ErrorCode.REDIS_UNAVAILABLE, (thrown as BusinessException).errorCode)
        coVerify(exactly = 0) { fallLogRepository.findLogIdsByUserId(any()) }
        coVerify(exactly = 0) { userRepository.createDeletionTaskAndDeleteUser(any()) }
    }

    @Test
    fun `회원 탈퇴 - 재인증 티켓이 없으면 REAUTH_REQUIRED, 세션도 끊지 않는다 (B6)`() = runTest {
        coEvery { userRepository.findByUserId("testUser") } returns baseUser

        val thrown = runCatching { userService.deleteUser("testUser", null) }.exceptionOrNull()

        assertEquals(ErrorCode.REAUTH_REQUIRED, (thrown as BusinessException).errorCode)
        coVerify(exactly = 0) { tokenRevocationStore.revokeAll(any()) }
        coVerify(exactly = 0) { userRepository.createDeletionTaskAndDeleteUser(any()) }
    }

    @Test
    fun `회원 탈퇴 - 파기 작업 기록과 계정 삭제가 한 트랜잭션으로 먼저 끝난다 (C5)`() = runTest {
        val taskSlot = slot<DeletionTask>()
        stubDeleteUser(logIds = listOf("log-1"))
        coEvery { userRepository.createDeletionTaskAndDeleteUser(capture(taskSlot)) } just Runs

        userService.deleteUser("testUser", "ticket-1")

        // 계정 문서가 사라진 뒤에도 정리할 수 있도록 룩업 키와 logId를 task가 들고 있어야 한다.
        assertEquals("testUser", taskSlot.captured.userId)
        assertEquals(baseUser.mail, taskSlot.captured.mail)
        assertEquals(baseUser.phone, taskSlot.captured.phone)
        assertEquals(listOf("log-1"), taskSlot.captured.logIds)
        coVerifyOrder {
            tokenRevocationStore.revokeAll("testUser")
            userRepository.createDeletionTaskAndDeleteUser(any())
        }
    }

    @Test
    fun `회원 탈퇴 - 정리가 끝나면 파기 작업을 삭제한다`() = runTest {
        stubDeleteUser()

        userService.deleteUser("testUser", "ticket-1")

        coVerify(exactly = 1) { deletionTaskRepository.delete("testUser") }
    }

    @Test
    fun `회원 탈퇴 - 정리 도중 실패해도 계정 삭제는 유지되고 작업이 남는다 (잡이 마무리)`() = runTest {
        stubDeleteUser()
        coEvery { guardianLinkRepository.deleteAllInvolving("testUser") } throws RuntimeException("firestore down")

        userService.deleteUser("testUser", "ticket-1")   // 예외를 밖으로 내지 않는다

        coVerify(exactly = 1) { userRepository.createDeletionTaskAndDeleteUser(any()) }
        coVerify(exactly = 0) { deletionTaskRepository.delete(any()) }
    }

    // 탈퇴 성공 경로 공통 스텁 — 각 테스트는 필요한 지점만 덮어쓴다.
    private fun stubDeleteUser(logIds: List<String> = emptyList()) {
        coEvery { userRepository.findByUserId("testUser") } returns baseUser
        coEvery { tokenRevocationStore.revokeAll("testUser") } just Runs
        coEvery { fallLogRepository.findLogIdsByUserId("testUser") } returns logIds
        coEvery { userRepository.createDeletionTaskAndDeleteUser(any()) } just Runs
        every { redis.delete(*anyVararg<String>()) } returns Mono.just(1L)
        every { valueOps.getAndDelete("pairing_code_owner:testUser") } returns Mono.empty()
        coEvery { fallLogRepository.deleteByUserId("testUser") } returns 0L
        coEvery { realtimeDataRepository.deleteByUserId("testUser") } just Runs
        coEvery { loginHistoryRepository.deleteByUserId("testUser") } returns 0L
        coEvery { settingsRepository.deleteByUserId("testUser") } just Runs
        coEvery { notificationRepository.deleteByUserId("testUser") } just Runs
        coEvery { notificationRepository.deleteByLogIds(any()) } just Runs
        coEvery { guardianLinkRepository.deleteAllInvolving("testUser") } just Runs
        coEvery { consentRepository.deleteByUserId("testUser") } just Runs
        coEvery { userRepository.deleteEmailLookup(any()) } just Runs
        coEvery { userRepository.deletePhoneLookup(any()) } just Runs
        logIds.forEach { coEvery { storageService.deleteBlob("fall-videos/$it.mp4") } returns true }
    }

    @Test
    fun `주소 수정 - address와 addressDetail이 정상 저장된다`() = runTest {
        val savedSlot = slot<User>()
        coEvery { userRepository.findByUserId("testUser") } returns baseUser
        coEvery { userRepository.saveWithLookups(any(), capture(savedSlot)) } returns true

        val result = userService.updateUser("testUser", UserUpdateRequest(address = "서울시 강남구 테헤란로", addressDetail = "101호", reauthTicket = "ticket-1"))

        assertEquals("서울시 강남구 테헤란로", result.address)
        assertEquals("101호", result.addressDetail)
    }

    @Test
    fun `주소 수정 - addressDetail 없이 address만 변경된다`() = runTest {
        val savedSlot = slot<User>()
        coEvery { userRepository.findByUserId("testUser") } returns baseUser
        coEvery { userRepository.saveWithLookups(any(), capture(savedSlot)) } returns true

        val result = userService.updateUser("testUser", UserUpdateRequest(address = "부산시 해운대구", reauthTicket = "ticket-1"))

        assertEquals("부산시 해운대구", result.address)
        assertNull(result.addressDetail)
    }

    @Test
    fun `비밀번호 변경 - currentPassword 일치 시 정상 변경된다`() = runTest {
        val savedSlot = slot<User>()
        coEvery { userRepository.findByUserId("testUser") } returns baseUser
        coEvery { userRepository.saveWithLookups(any(), capture(savedSlot)) } returns true

        val result = userService.updateUser("testUser", UserUpdateRequest(currentPassword = "oldPass", password = "newPass1234"))

        assertNotNull(result)
    }

    @Test
    fun `비밀번호 변경 - currentPassword 불일치 시 예외 발생`() = runTest {
        coEvery { userRepository.findByUserId("testUser") } returns baseUser

        val thrown = runCatching {
            userService.updateUser("testUser", UserUpdateRequest(currentPassword = "wrongPass", password = "newPass1234"))
        }.exceptionOrNull()

        assertNotNull(thrown)
        assertTrue(thrown is BusinessException)
    }

    @Test
    fun `비밀번호 변경 - currentPassword 없이 password만 전달 시 예외 발생`() = runTest {
        coEvery { userRepository.findByUserId("testUser") } returns baseUser

        val thrown = runCatching {
            userService.updateUser("testUser", UserUpdateRequest(password = "newPass1234"))
        }.exceptionOrNull()

        assertNotNull(thrown)
        assertTrue(thrown is BusinessException)
    }

    @Test
    fun `프로필 조회 - UserResponse에 address와 addressDetail이 포함된다`() = runTest {
        coEvery { userRepository.findByUserId("testUser") } returns baseUser.copy(address = "서울시 마포구", addressDetail = "202호")

        val result = userService.getUser("testUser")

        assertEquals("서울시 마포구", result.address)
        assertEquals("202호", result.addressDetail)
    }

    // ── 본인 확인·메일 변경·중복 (B1) ─────────────────────────────

    @Test
    fun `개인정보 수정 - 재인증 티켓이 없으면 REAUTH_REQUIRED (B1)`() = runTest {
        coEvery { userRepository.findByUserId("testUser") } returns baseUser

        val thrown = runCatching {
            userService.updateUser("testUser", UserUpdateRequest(name = "새이름"))
        }.exceptionOrNull()

        // 앱은 수정 화면 진입 전 verify-password를 부르지만, 저장 요청에는 그 사실이 담기지 않았다.
        assertEquals(ErrorCode.REAUTH_REQUIRED, (thrown as BusinessException).errorCode)
        coVerify(exactly = 0) { userRepository.saveWithLookups(any(), any()) }
    }

    @Test
    fun `개인정보 수정 - 티켓의 userId가 다르면 REAUTH_REQUIRED`() = runTest {
        coEvery { userRepository.findByUserId("testUser") } returns baseUser
        every { valueOps.get("reauth_ticket:other") } returns Mono.just("otherUser")

        val thrown = runCatching {
            userService.updateUser("testUser", UserUpdateRequest(name = "새이름", reauthTicket = "other"))
        }.exceptionOrNull()

        assertEquals(ErrorCode.REAUTH_REQUIRED, (thrown as BusinessException).errorCode)
    }

    @Test
    fun `개인정보 수정 - 메일이 그대로면 인증 티켓 없이 통과한다 (현재 앱 유지)`() = runTest {
        coEvery { userRepository.findByUserId("testUser") } returns baseUser

        // 앱은 메일을 바꾸지 않아도 현재 값을 함께 보낸다 — 값이 같으면 인증을 요구하지 않는다.
        val result = userService.updateUser(
            "testUser",
            UserUpdateRequest(name = "새이름", mail = baseUser.mail, reauthTicket = "ticket-1")
        )

        assertEquals("새이름", result.name)
        coVerify(exactly = 1) { userRepository.saveWithLookups(any(), any()) }
    }

    @Test
    fun `개인정보 수정 - 메일을 바꾸는데 인증 티켓이 없으면 EMAIL_NOT_VERIFIED (B1)`() = runTest {
        coEvery { userRepository.findByUserId("testUser") } returns baseUser
        every { valueOps.getAndDelete("verify_ticket:null") } returns Mono.empty()

        val thrown = runCatching {
            userService.updateUser(
                "testUser",
                UserUpdateRequest(mail = "new@example.com", reauthTicket = "ticket-1")
            )
        }.exceptionOrNull()

        assertEquals(ErrorCode.EMAIL_NOT_VERIFIED, (thrown as BusinessException).errorCode)
        coVerify(exactly = 0) { userRepository.saveWithLookups(any(), any()) }
    }

    @Test
    fun `개인정보 수정 - 인증 티켓의 메일과 요청 메일이 다르면 EMAIL_NOT_VERIFIED`() = runTest {
        coEvery { userRepository.findByUserId("testUser") } returns baseUser
        every { valueOps.getAndDelete("verify_ticket:mail-ticket") } returns Mono.just("other@example.com")

        val thrown = runCatching {
            userService.updateUser(
                "testUser",
                UserUpdateRequest(mail = "new@example.com", reauthTicket = "ticket-1", emailVerifyTicket = "mail-ticket")
            )
        }.exceptionOrNull()

        assertEquals(ErrorCode.EMAIL_NOT_VERIFIED, (thrown as BusinessException).errorCode)
    }

    @Test
    fun `개인정보 수정 - 메일 인증 티켓이 맞으면 저장된다`() = runTest {
        coEvery { userRepository.findByUserId("testUser") } returns baseUser
        every { valueOps.getAndDelete("verify_ticket:mail-ticket") } returns Mono.just("new@example.com")

        val result = userService.updateUser(
            "testUser",
            UserUpdateRequest(mail = "new@example.com", reauthTicket = "ticket-1", emailVerifyTicket = "mail-ticket")
        )

        assertEquals("new@example.com", result.mail)
    }

    @Test
    fun `개인정보 수정 - 다른 계정이 쓰는 전화번호면 PHONE_ALREADY_EXISTS (B1)`() = runTest {
        coEvery { userRepository.findByUserId("testUser") } returns baseUser
        coEvery { userRepository.saveWithLookups(any(), any()) } returns false

        val thrown = runCatching {
            userService.updateUser("testUser", UserUpdateRequest(phone = "010-9999-8888", reauthTicket = "ticket-1"))
        }.exceptionOrNull()

        assertEquals(ErrorCode.PHONE_ALREADY_EXISTS, (thrown as BusinessException).errorCode)
    }

    // ── 비밀번호 확인·재인증 티켓 발급 (B5·B1) ────────────────────

    @Test
    fun `비밀번호 확인 - 성공 시 재인증 티켓을 발급하고 rate limit을 거친다`() = runTest {
        coEvery { userRepository.findByUserId("testUser") } returns baseUser
        every { valueOps.set(any(), "testUser", any<java.time.Duration>()) } returns Mono.just(true)

        val response = userService.verifyPassword("testUser", "oldPass")

        assertTrue(response.reauthTicket.isNotBlank())
        coVerify { rateLimiter.requireAllowed("rl:verify-password:testUser", 5, 600) }
        verify { valueOps.set("reauth_ticket:${response.reauthTicket}", "testUser", java.time.Duration.ofSeconds(600)) }
    }

    @Test
    fun `비밀번호 확인 - 비밀번호가 틀리면 INVALID_PASSWORD, 티켓을 발급하지 않는다`() = runTest {
        coEvery { userRepository.findByUserId("testUser") } returns baseUser

        val thrown = runCatching { userService.verifyPassword("testUser", "wrongPass") }.exceptionOrNull()

        assertEquals(ErrorCode.INVALID_PASSWORD, (thrown as BusinessException).errorCode)
        verify(exactly = 0) { valueOps.set(any(), any(), any<java.time.Duration>()) }
    }

    @Test
    fun `개인정보 수정 - 저장이 실패하면 재인증 티켓을 지우지 않는다 (재시도 가능)`() = runTest {
        coEvery { userRepository.findByUserId("testUser") } returns baseUser
        coEvery { userRepository.saveWithLookups(any(), any()) } returns false

        runCatching {
            userService.updateUser("testUser", UserUpdateRequest(phone = "010-9999-8888", reauthTicket = "ticket-1"))
        }

        // 409로 실패했는데 티켓까지 타면 번호만 고쳐 재시도해도 비밀번호부터 다시 확인해야 한다.
        verify(exactly = 0) { redis.delete("reauth_ticket:ticket-1") }
    }

    @Test
    fun `개인정보 수정 - 저장이 끝나면 재인증 티켓을 지운다`() = runTest {
        coEvery { userRepository.findByUserId("testUser") } returns baseUser

        userService.updateUser("testUser", UserUpdateRequest(name = "새이름", reauthTicket = "ticket-1"))

        verify(exactly = 1) { redis.delete("reauth_ticket:ticket-1") }
    }

    // ── 보안 이벤트 기록 (C6) ────────────────────────────────────

    private fun captureEvents(): MutableList<LoginHistory> {
        val events = mutableListOf<LoginHistory>()
        coEvery { loginHistoryRepository.save(capture(events)) } answers { firstArg() }
        return events
    }

    @Test
    fun `보안 이벤트 - 비밀번호 변경이 PASSWORD_CHANGE로 기록된다 (C6)`() = runTest {
        val events = captureEvents()
        coEvery { userRepository.findByUserId("testUser") } returns baseUser

        userService.updateUser("testUser", UserUpdateRequest(currentPassword = "oldPass", password = "newPass1234"))

        // 비밀번호 변경은 계정 탈취의 핵심 단계라 흔적이 반드시 남아야 한다.
        assertEquals(SecurityEventType.PASSWORD_CHANGE, events.single().eventType)
    }

    @Test
    fun `보안 이벤트 - 주소만 바꾸면 기록하지 않는다`() = runTest {
        val events = captureEvents()
        coEvery { userRepository.findByUserId("testUser") } returns baseUser

        userService.updateUser("testUser", UserUpdateRequest(address = "서울시 마포구", reauthTicket = "ticket-1"))

        assertTrue(events.isEmpty())
    }

    @Test
    fun `보안 이벤트 - 탈퇴는 계정 문서 삭제 전에 ACCOUNT_DELETED로 기록된다 (C6)`() = runTest {
        val events = captureEvents()
        stubDeleteUser()

        userService.deleteUser("testUser", "ticket-1")

        assertEquals(SecurityEventType.ACCOUNT_DELETED, events.single().eventType)
        // 이력은 별도 컬렉션이라 계정이 사라진 뒤에도 조사에 쓸 수 있다.
        coVerifyOrder {
            loginHistoryRepository.save(any())
            userRepository.createDeletionTaskAndDeleteUser(any())
        }
    }
}
