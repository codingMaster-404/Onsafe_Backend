package com.onsafe.backend.domain.auth

import com.onsafe.backend.common.exception.BusinessException
import com.onsafe.backend.common.exception.ErrorCode
import com.onsafe.backend.common.ratelimit.RateLimiter
import com.onsafe.backend.common.security.JwtProvider
import com.onsafe.backend.common.security.TokenParseResult
import com.onsafe.backend.common.security.TokenRevocationStore
import com.onsafe.backend.common.security.TokenType
import com.onsafe.backend.common.security.VerificationCodeGenerator
import com.onsafe.backend.domain.auth.model.dto.*
import com.onsafe.backend.domain.auth.model.entity.LoginHistory
import com.onsafe.backend.domain.auth.model.entity.SecurityEventType
import com.onsafe.backend.domain.auth.repository.LoginHistoryRepository
import com.onsafe.backend.domain.auth.service.AuthService
import com.onsafe.backend.domain.auth.service.EmailService
import com.onsafe.backend.domain.consent.repository.ConsentRepository
import com.onsafe.backend.domain.notification.service.NotificationService
import com.onsafe.backend.domain.settings.repository.SettingsRepository
import com.onsafe.backend.domain.user.model.entity.User
import com.onsafe.backend.domain.user.repository.UserRepository
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.data.redis.core.ReactiveValueOperations
import org.springframework.security.crypto.password.PasswordEncoder
import reactor.core.publisher.Mono
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime

class AuthServiceTest {

    private val userRepository: UserRepository = mockk()
    private val passwordEncoder: PasswordEncoder = mockk()
    private val jwtProvider: JwtProvider = mockk()
    private val emailService: EmailService = mockk()
    private val redis: ReactiveStringRedisTemplate = mockk()
    private val valueOps: ReactiveValueOperations<String, String> = mockk()
    private val loginHistoryRepository: LoginHistoryRepository = mockk()
    private val settingsRepository: SettingsRepository = mockk()
    private val consentRepository: ConsentRepository = mockk()
    private val rateLimiter: RateLimiter = mockk()
    private val tokenRevocationStore: TokenRevocationStore = mockk()
    private val notificationService: NotificationService = mockk(relaxUnitFun = true)
    private lateinit var authService: AuthService

    private val baseUser = User(
        userId = "testUser",
        password = "encoded_password",
        name = "홍길동",
        phone = "010-1234-5678",
        mail = "test@example.com",
        createdAt = LocalDateTime.now()
    )

    @BeforeEach
    fun setUp() {
        every { redis.opsForValue() } returns valueOps
        coEvery { loginHistoryRepository.save(any()) } answers { firstArg<LoginHistory>() }
        // 레이트리밋은 기본 허용 — 리밋 초과 자체를 검증하는 테스트가 아니므로 항상 통과시켜 기존 케이스 로직에 집중한다.
        coEvery { rateLimiter.requireAllowed(any(), any(), any()) } just Runs
        authService = AuthService(
            userRepository, passwordEncoder, jwtProvider, emailService, redis,
            loginHistoryRepository, settingsRepository, consentRepository, rateLimiter, VerificationCodeGenerator(),
            tokenRevocationStore,
            notificationService
        )
    }

    // ── 로그인 ────────────────────────────────────────────────────

    @Test
    fun `로그인 - 존재하지 않는 아이디면 USER_NOT_FOUND 예외 발생 (D21)`() = runTest {
        coEvery { userRepository.findByUserId("unknown") } returns null

        val thrown = runCatching {
            authService.login(LoginRequest(userId = "unknown", password = "pass", deviceId = "device1"), "127.0.0.1", "test-agent")
        }.exceptionOrNull()

        // 아이디 없음과 비밀번호 불일치를 사용자가 구분해서 볼 수 있어야 한다는 UX 판단(D21).
        assertTrue(thrown is BusinessException)
        assertEquals(ErrorCode.USER_NOT_FOUND, (thrown as BusinessException).errorCode)
    }

    @Test
    fun `로그인 - 비밀번호 불일치 시 LOGIN_FAILED 예외 발생 (B7)`() = runTest {
        coEvery { userRepository.findByUserId("testUser") } returns baseUser
        every { passwordEncoder.matches("wrongPass", "encoded_password") } returns false

        val thrown = runCatching {
            authService.login(LoginRequest(userId = "testUser", password = "wrongPass", deviceId = "device1"), "127.0.0.1", "test-agent")
        }.exceptionOrNull()

        assertTrue(thrown is BusinessException)
        assertEquals(ErrorCode.LOGIN_FAILED, (thrown as BusinessException).errorCode)
    }

    @Test
    fun `로그인 - 성공 시 userId로 토큰을 발급하고 로그인 시각을 auth_time으로 넘긴다`() = runTest {
        coEvery { userRepository.findByUserId("testUser") } returns baseUser
        every { passwordEncoder.matches("pass", "encoded_password") } returns true
        every { jwtProvider.generateAccessToken("testUser", any()) } returns "new-access"
        every { jwtProvider.generateRefreshToken("testUser", any()) } returns "new-refresh"

        val before = Instant.now()
        val response = authService.login(LoginRequest(userId = "testUser", password = "pass", deviceId = "device1"), "127.0.0.1", "test-agent")
        val after = Instant.now()

        assertEquals("new-access", response.accessToken)
        assertEquals("new-refresh", response.refreshToken)
        verify { jwtProvider.generateRefreshToken("testUser", match { !it.isBefore(before) && !it.isAfter(after) }) }
    }

    // ── 회원가입 ──────────────────────────────────────────────────

    @Test
    fun `회원가입 - 중복 아이디면 USER_ID_ALREADY_EXISTS 예외 발생`() = runTest {
        coEvery { userRepository.existsByUserId("testUser") } returns true

        val thrown = runCatching {
            authService.register(
                RegisterRequest(userId = "testUser", password = "pass1234", name = "홍길동", mail = "a@b.com", phone = "010-1234-5678", termsAgreed = true, privacyPolicyAgreed = true, sensitiveInfoAgreed = true, emailVerifyTicket = "test-ticket"),
                "127.0.0.1"
            )
        }.exceptionOrNull()

        assertTrue(thrown is BusinessException)
        assertEquals(ErrorCode.USER_ID_ALREADY_EXISTS, (thrown as BusinessException).errorCode)
    }

    @Test
    fun `회원가입 - 중복 이메일이면 MAIL_ALREADY_EXISTS 예외 발생`() = runTest {
        coEvery { userRepository.existsByUserId("testUser") } returns false
        coEvery { userRepository.existsByMail("test@example.com") } returns true

        val thrown = runCatching {
            authService.register(
                RegisterRequest(userId = "testUser", password = "pass1234", name = "홍길동", mail = "test@example.com", phone = "010-1234-5678", termsAgreed = true, privacyPolicyAgreed = true, sensitiveInfoAgreed = true, emailVerifyTicket = "test-ticket"),
                "127.0.0.1"
            )
        }.exceptionOrNull()

        assertTrue(thrown is BusinessException)
        assertEquals(ErrorCode.MAIL_ALREADY_EXISTS, (thrown as BusinessException).errorCode)
    }

    @Test
    fun `회원가입 - 중복 전화번호면 PHONE_ALREADY_EXISTS 예외 발생`() = runTest {
        coEvery { userRepository.existsByUserId("testUser") } returns false
        coEvery { userRepository.existsByMail("test@example.com") } returns false
        coEvery { userRepository.existsByPhone("010-1234-5678") } returns true

        val thrown = runCatching {
            authService.register(
                RegisterRequest(userId = "testUser", password = "pass1234", name = "홍길동", mail = "test@example.com", phone = "010-1234-5678", termsAgreed = true, privacyPolicyAgreed = true, sensitiveInfoAgreed = true, emailVerifyTicket = "test-ticket"),
                "127.0.0.1"
            )
        }.exceptionOrNull()

        assertTrue(thrown is BusinessException)
        assertEquals(ErrorCode.PHONE_ALREADY_EXISTS, (thrown as BusinessException).errorCode)
    }

    @Test
    fun `회원가입 - 이메일 인증을 완료하지 않았으면 EMAIL_NOT_VERIFIED 예외 발생`() = runTest {
        coEvery { userRepository.existsByUserId("testUser") } returns false
        coEvery { userRepository.existsByMail("test@example.com") } returns false
        coEvery { userRepository.existsByPhone("010-1234-5678") } returns false
        every { valueOps.getAndDelete("verify_ticket:test-ticket") } returns Mono.empty()

        val thrown = runCatching {
            authService.register(
                RegisterRequest(userId = "testUser", password = "pass1234", name = "홍길동", mail = "test@example.com", phone = "010-1234-5678", termsAgreed = true, privacyPolicyAgreed = true, sensitiveInfoAgreed = true, emailVerifyTicket = "test-ticket"),
                "127.0.0.1"
            )
        }.exceptionOrNull()

        assertTrue(thrown is BusinessException)
        assertEquals(ErrorCode.EMAIL_NOT_VERIFIED, (thrown as BusinessException).errorCode)
    }

    // ── 아이디 찾기 ───────────────────────────────────────────────

    // 티켓이 유효한 상태를 만든다 — 이제 티켓 없이는 아이디 찾기 자체가 동작하지 않는다(C1).
    private fun stubFindIdTicket(ticket: String, mail: String?) {
        every { valueOps.getAndDelete("verify_ticket:$ticket") } returns
            if (mail == null) Mono.empty() else Mono.just(mail)
    }

    @Test
    fun `아이디 찾기 - 이메일 인증 티켓이 없으면 EMAIL_NOT_VERIFIED (C1)`() = runTest {
        coEvery { userRepository.findByMail("test@example.com") } returns baseUser
        stubFindIdTicket("gone", null)

        val thrown = runCatching {
            authService.findId(FindIdRequest(name = "홍길동", mail = "test@example.com", emailVerifyTicket = "gone"), "127.0.0.1")
        }.exceptionOrNull()

        assertTrue(thrown is BusinessException)
        assertEquals(ErrorCode.EMAIL_NOT_VERIFIED, (thrown as BusinessException).errorCode)
    }

    @Test
    fun `아이디 찾기 - 티켓의 메일과 요청 메일이 다르면 EMAIL_NOT_VERIFIED (C1)`() = runTest {
        coEvery { userRepository.findByMail("test@example.com") } returns baseUser
        stubFindIdTicket("other", "someone@example.com")

        val thrown = runCatching {
            authService.findId(FindIdRequest(name = "홍길동", mail = "test@example.com", emailVerifyTicket = "other"), "127.0.0.1")
        }.exceptionOrNull()

        assertEquals(ErrorCode.EMAIL_NOT_VERIFIED, (thrown as BusinessException).errorCode)
    }

    @Test
    fun `아이디 찾기 - 이메일 미존재 시 USER_NOT_FOUND 예외 발생`() = runTest {
        coEvery { userRepository.findByMail("notexist@example.com") } returns null
        stubFindIdTicket("t1", "notexist@example.com")

        val thrown = runCatching {
            authService.findId(FindIdRequest(name = "홍길동", mail = "notexist@example.com", emailVerifyTicket = "t1"), "127.0.0.1")
        }.exceptionOrNull()

        assertTrue(thrown is BusinessException)
        assertEquals(ErrorCode.USER_NOT_FOUND, (thrown as BusinessException).errorCode)
    }

    @Test
    fun `아이디 찾기 - 이름 불일치 시 USER_NOT_FOUND 예외 발생 (이메일 존재 여부 노출 차단)`() = runTest {
        coEvery { userRepository.findByMail("test@example.com") } returns baseUser
        stubFindIdTicket("t2", "test@example.com")

        val thrown = runCatching {
            authService.findId(FindIdRequest(name = "다른이름", mail = "test@example.com", emailVerifyTicket = "t2"), "127.0.0.1")
        }.exceptionOrNull()

        assertTrue(thrown is BusinessException)
        assertEquals(ErrorCode.USER_NOT_FOUND, (thrown as BusinessException).errorCode)
    }

    @Test
    fun `아이디 찾기 - 티켓과 정보가 맞으면 마스킹된 아이디를 돌려준다 (C1)`() = runTest {
        coEvery { userRepository.findByMail("test@example.com") } returns baseUser
        stubFindIdTicket("t3", "test@example.com")

        val response = authService.findId(
            FindIdRequest(name = "홍길동", mail = "test@example.com", emailVerifyTicket = "t3"), "127.0.0.1"
        )

        assertEquals("tes*****", response.userId)
        // IP 기준 rate limit도 거친다 — 티켓을 반복 발급받아 돌리는 시도를 막는다.
        coVerify { rateLimiter.requireAllowed("rl:find-id:ip:127.0.0.1", 10, 3600) }
    }

    // ── 비밀번호 재설정 코드 발송 ──────────────────────────────────

    @Test
    fun `비밀번호 재설정 코드 발송 - 존재하지 않는 아이디여도 같은 성공 응답, 메일은 보내지 않는다 (C2)`() = runTest {
        coEvery { userRepository.findByUserId("unknown") } returns null

        authService.sendResetCode(SendResetCodeRequest(userId = "unknown", mail = "test@example.com"), "127.0.0.1")

        // 응답으로 가입 여부를 알 수 없어야 한다 — 예외를 던지지 않고, 메일도 나가지 않는다.
        coVerify(exactly = 0) { emailService.sendResetCode(any(), any()) }
        verify(exactly = 0) { valueOps.set("reset_code:unknown", any(), any<Duration>()) }
    }

    @Test
    fun `비밀번호 재설정 코드 발송 - 이메일 불일치여도 같은 성공 응답, 메일은 보내지 않는다 (C2)`() = runTest {
        coEvery { userRepository.findByUserId("testUser") } returns baseUser

        authService.sendResetCode(SendResetCodeRequest(userId = "testUser", mail = "wrong@example.com"), "127.0.0.1")

        coVerify(exactly = 0) { emailService.sendResetCode(any(), any()) }
        verify(exactly = 0) { valueOps.set("reset_code:testUser", any(), any<Duration>()) }
    }

    @Test
    fun `비밀번호 재설정 코드 발송 - 아이디·메일이 맞으면 코드를 저장하고 메일을 보낸다`() = runTest {
        coEvery { userRepository.findByUserId("testUser") } returns baseUser
        // 코드 생성기는 실제 구현을 쓰므로(setUp) 값 자체는 고정하지 않는다.
        every { valueOps.set(eq("reset_code:testUser"), any(), Duration.ofSeconds(180)) } returns Mono.just(true)
        coEvery { emailService.sendResetCode(eq("test@example.com"), any()) } just Runs

        authService.sendResetCode(SendResetCodeRequest(userId = "testUser", mail = "test@example.com"), "127.0.0.1")

        coVerify(exactly = 1) { emailService.sendResetCode(eq("test@example.com"), any()) }
    }

    @Test
    fun `비밀번호 재설정 코드 발송 - IP 기준 rate limit도 확인한다 (C2)`() = runTest {
        coEvery { userRepository.findByUserId("unknown") } returns null

        authService.sendResetCode(SendResetCodeRequest(userId = "unknown", mail = "test@example.com"), "1.2.3.4")

        coVerify { rateLimiter.requireAllowed("rl:send-reset:ip:1.2.3.4", 10, 3600) }
    }

    // ── 이메일 인증코드 검증 ──────────────────────────────────────

    @Test
    fun `이메일 인증코드 검증 - Redis에 코드 없으면 INVALID_EMAIL_CODE 예외 발생`() = runTest {
        every { valueOps.get("email_verify:test@example.com") } returns Mono.empty()

        val thrown = runCatching {
            authService.verifyEmailCode(VerifyEmailCodeRequest(mail = "test@example.com", code = "123456"))
        }.exceptionOrNull()

        assertTrue(thrown is BusinessException)
        assertEquals(ErrorCode.INVALID_EMAIL_CODE, (thrown as BusinessException).errorCode)
    }

    @Test
    fun `이메일 인증코드 검증 - 코드 불일치 시 INVALID_EMAIL_CODE 예외 발생`() = runTest {
        every { valueOps.get("email_verify:test@example.com") } returns Mono.just("999999")

        val thrown = runCatching {
            authService.verifyEmailCode(VerifyEmailCodeRequest(mail = "test@example.com", code = "123456"))
        }.exceptionOrNull()

        assertTrue(thrown is BusinessException)
        assertEquals(ErrorCode.INVALID_EMAIL_CODE, (thrown as BusinessException).errorCode)
    }

    // ── 비밀번호 재설정 인증코드 검증 ────────────────────────────

    @Test
    fun `비밀번호 재설정 인증코드 검증 - Redis에 코드 없으면 INVALID_RESET_CODE 예외 발생`() = runTest {
        every { valueOps.get("reset_code:testUser") } returns Mono.empty()

        val thrown = runCatching {
            authService.verifyResetCode(VerifyResetCodeRequest(userId = "testUser", code = "123456"))
        }.exceptionOrNull()

        assertTrue(thrown is BusinessException)
        assertEquals(ErrorCode.INVALID_RESET_CODE, (thrown as BusinessException).errorCode)
    }

    @Test
    fun `비밀번호 재설정 인증코드 검증 - 코드 불일치 시 INVALID_RESET_CODE 예외 발생`() = runTest {
        every { valueOps.get("reset_code:testUser") } returns Mono.just("999999")

        val thrown = runCatching {
            authService.verifyResetCode(VerifyResetCodeRequest(userId = "testUser", code = "123456"))
        }.exceptionOrNull()

        assertTrue(thrown is BusinessException)
        assertEquals(ErrorCode.INVALID_RESET_CODE, (thrown as BusinessException).errorCode)
    }

    @Test
    fun `비밀번호 재설정 인증코드 검증 - 성공 시 티켓을 발급하고 코드 키를 지운다 (A3)`() = runTest {
        every { valueOps.get("reset_code:testUser") } returns Mono.just("123456")
        every { redis.delete("reset_code:testUser") } returns Mono.just(1L)
        every { valueOps.set(match { it.startsWith("reset_ticket:") }, "testUser", Duration.ofSeconds(600)) } returns Mono.just(true)

        val response = authService.verifyResetCode(VerifyResetCodeRequest(userId = "testUser", code = "123456"))

        // 티켓에는 userId가 담긴다 — resetPassword가 요청의 userId와 대조한다.
        assertTrue(response.resetTicket.isNotBlank())
        verify { valueOps.set("reset_ticket:${response.resetTicket}", "testUser", Duration.ofSeconds(600)) }
    }

    // ── 비밀번호 재설정 ───────────────────────────────────────────

    @Test
    fun `비밀번호 재설정 - 티켓이 없거나 이미 쓰였으면 INVALID_RESET_CODE (A3)`() = runTest {
        coEvery { userRepository.findByUserId("testUser") } returns baseUser
        every { valueOps.getAndDelete("reset_ticket:gone") } returns Mono.empty()

        val thrown = runCatching {
            authService.resetPassword(ResetPasswordRequest(userId = "testUser", resetTicket = "gone", newPassword = "newPass1234"))
        }.exceptionOrNull()

        assertTrue(thrown is BusinessException)
        assertEquals(ErrorCode.INVALID_RESET_CODE, (thrown as BusinessException).errorCode)
        coVerify(exactly = 0) { userRepository.save(any()) }
    }

    @Test
    fun `비밀번호 재설정 - 티켓의 userId와 요청 userId가 다르면 INVALID_RESET_CODE (A3)`() = runTest {
        coEvery { userRepository.findByUserId("testUser") } returns baseUser
        every { valueOps.getAndDelete("reset_ticket:other") } returns Mono.just("otherUser")

        val thrown = runCatching {
            authService.resetPassword(ResetPasswordRequest(userId = "testUser", resetTicket = "other", newPassword = "newPass1234"))
        }.exceptionOrNull()

        assertEquals(ErrorCode.INVALID_RESET_CODE, (thrown as BusinessException).errorCode)
        coVerify(exactly = 0) { userRepository.save(any()) }
    }

    @Test
    fun `비밀번호 재설정 - 없는 사용자도 USER_NOT_FOUND가 아니라 INVALID_RESET_CODE (C2 통로 차단)`() = runTest {
        coEvery { userRepository.findByUserId("unknown") } returns null

        val thrown = runCatching {
            authService.resetPassword(ResetPasswordRequest(userId = "unknown", resetTicket = "any", newPassword = "newPass1234"))
        }.exceptionOrNull()

        assertEquals(ErrorCode.INVALID_RESET_CODE, (thrown as BusinessException).errorCode)
        // 티켓을 소비하지도 않는다 — 존재 여부를 알아내려는 요청으로 남의 티켓을 태울 수 없다.
        verify(exactly = 0) { valueOps.getAndDelete(any()) }
    }

    @Test
    fun `비밀번호 재설정 - 성공 시 티켓을 소비하고 저장 전에 모든 세션을 무효화한다`() = runTest {
        coEvery { userRepository.findByUserId("testUser") } returns baseUser
        every { valueOps.getAndDelete("reset_ticket:ticket-1") } returns Mono.just("testUser")
        coEvery { tokenRevocationStore.revokeAll("testUser") } just Runs
        every { passwordEncoder.encode("newPass1234") } returns "encoded_new"
        coEvery { userRepository.save(any()) } answers { firstArg() }

        authService.resetPassword(ResetPasswordRequest(userId = "testUser", resetTicket = "ticket-1", newPassword = "newPass1234"))

        // 티켓 소비(GETDEL)는 저장 직전 — Firestore 일시 오류로 티켓이 타 버리지 않도록 조회 뒤에 둔다.
        coVerifyOrder {
            userRepository.findByUserId("testUser")
            tokenRevocationStore.revokeAll("testUser")
            userRepository.save(match { it.password == "encoded_new" })
        }
        verify(exactly = 1) { valueOps.getAndDelete("reset_ticket:ticket-1") }
    }

    // ── 토큰 갱신 ────────────────────────────────────

    // refresh 성공 경로의 공통 목 — 각 테스트는 필요한 지점만 덮어써 실패 분기를 만든다.
    private fun stubValidRefresh(token: String, authTime: Instant = Instant.parse("2026-09-01T00:00:00Z")) {
        // parse 1회로 검증 + userId·iat·auth_time·남은 만료를 함께 돌려준다 (C7 ③)
        every { jwtProvider.parse(token, TokenType.REFRESH) } returns TokenParseResult.Valid(
            userId = "testUser",
            issuedAt = Instant.parse("2026-09-10T00:00:00Z"),
            authTime = authTime,
            remainingExpiry = Duration.ofDays(10)
        )
        every { jwtProvider.blacklistKey(token) } returns "bl:hashed-$token"
        coEvery { tokenRevocationStore.isRevoked("testUser", Instant.parse("2026-09-10T00:00:00Z")) } returns false
        coEvery { userRepository.findByUserId("testUser") } returns baseUser
        every { valueOps.setIfAbsent("bl:hashed-$token", "1", Duration.ofDays(10)) } returns Mono.just(true)
        every { jwtProvider.generateAccessToken("testUser", authTime) } returns "new-access"
        every { jwtProvider.generateRefreshToken("testUser", authTime) } returns "new-refresh"
    }

    @Test
    fun `토큰 갱신 - 서명 위조 등 무효 토큰이면 INVALID_TOKEN 예외 발생`() = runTest {
        every { jwtProvider.parse("invalid-token", TokenType.REFRESH) } returns TokenParseResult.Invalid(ErrorCode.INVALID_TOKEN)

        val thrown = runCatching { authService.refresh("invalid-token") }.exceptionOrNull()

        assertTrue(thrown is BusinessException)
        assertEquals(ErrorCode.INVALID_TOKEN, (thrown as BusinessException).errorCode)
    }

    @Test
    fun `토큰 갱신 - 만료된 토큰이면 EXPIRED_TOKEN 예외 발생`() = runTest {
        every { jwtProvider.parse("expired-token", TokenType.REFRESH) } returns TokenParseResult.Invalid(ErrorCode.EXPIRED_TOKEN)

        val thrown = runCatching { authService.refresh("expired-token") }.exceptionOrNull()

        assertTrue(thrown is BusinessException)
        assertEquals(ErrorCode.EXPIRED_TOKEN, (thrown as BusinessException).errorCode)
    }

    @Test
    fun `토큰 갱신 - access 토큰으로 호출하면 INVALID_TOKEN 예외 발생 (A1)`() = runTest {
        // refresh는 REFRESH 타입으로만 검증해야 한다 — ACCESS로 요청하면 이 목이 매칭되지 않아 실패한다.
        every { jwtProvider.parse("access-token", TokenType.REFRESH) } returns TokenParseResult.Invalid(ErrorCode.INVALID_TOKEN)

        val thrown = runCatching { authService.refresh("access-token") }.exceptionOrNull()

        assertEquals(ErrorCode.INVALID_TOKEN, (thrown as BusinessException).errorCode)
    }

    @Test
    fun `토큰 갱신 - 세션이 무효화된 사용자면 INVALID_TOKEN, 토큰을 소모하지 않는다 (A2)`() = runTest {
        stubValidRefresh("revoked-token")
        coEvery { tokenRevocationStore.isRevoked("testUser", any()) } returns true

        val thrown = runCatching { authService.refresh("revoked-token") }.exceptionOrNull()

        assertEquals(ErrorCode.INVALID_TOKEN, (thrown as BusinessException).errorCode)
        verify(exactly = 0) { valueOps.setIfAbsent(any(), any(), any<Duration>()) }
    }

    @Test
    fun `토큰 갱신 - 탈퇴 등으로 사용자가 없으면 INVALID_TOKEN, 토큰을 소모하지 않는다 (A2)`() = runTest {
        stubValidRefresh("orphan-token")
        coEvery { userRepository.findByUserId("testUser") } returns null

        val thrown = runCatching { authService.refresh("orphan-token") }.exceptionOrNull()

        assertEquals(ErrorCode.INVALID_TOKEN, (thrown as BusinessException).errorCode)
        verify(exactly = 0) { valueOps.setIfAbsent(any(), any(), any<Duration>()) }
    }

    @Test
    fun `토큰 갱신 - 선점에 실패하면(동시 요청·이미 사용) INVALID_TOKEN (B4)`() = runTest {
        stubValidRefresh("used-token")
        every { valueOps.setIfAbsent("bl:hashed-used-token", "1", Duration.ofDays(10)) } returns Mono.just(false)

        val thrown = runCatching { authService.refresh("used-token") }.exceptionOrNull()

        assertEquals(ErrorCode.INVALID_TOKEN, (thrown as BusinessException).errorCode)
    }

    @Test
    fun `토큰 갱신 - 검증 직후 만료돼 남은 시간이 없으면 EXPIRED_TOKEN`() = runTest {
        stubValidRefresh("edge-token")
        every { jwtProvider.parse("edge-token", TokenType.REFRESH) } returns TokenParseResult.Valid(
            userId = "testUser",
            issuedAt = Instant.parse("2026-09-10T00:00:00Z"),
            authTime = Instant.parse("2026-09-01T00:00:00Z"),
            remainingExpiry = Duration.ZERO
        )

        val thrown = runCatching { authService.refresh("edge-token") }.exceptionOrNull()

        assertEquals(ErrorCode.EXPIRED_TOKEN, (thrown as BusinessException).errorCode)
    }

    @Test
    fun `토큰 갱신 - 성공 시 auth_time을 이어받아 새 토큰을 발급한다 (D4)`() = runTest {
        val authTime = Instant.parse("2026-09-01T00:00:00Z")
        stubValidRefresh("good-refresh", authTime)

        val response = authService.refresh("good-refresh")

        assertEquals("new-access", response.accessToken)
        assertEquals("new-refresh", response.refreshToken)
        verify { jwtProvider.generateRefreshToken("testUser", authTime) }
    }

    // ── 로그아웃 + FCM 해제 (B2) ─────────────────────────────────

    @Test
    fun `로그아웃 - FCM 토큰을 함께 보내면 그 기기 토큰도 해제한다 (B2)`() = runTest {
        every { jwtProvider.getRemainingExpiry(any()) } returns Duration.ofMinutes(30)
        every { jwtProvider.blacklistKey(any()) } answers { "bl:hashed-" + firstArg<String>() }
        every { valueOps.set(any(), "1", any<Duration>()) } returns Mono.just(true)
        every { jwtProvider.parse("access.token", TokenType.ACCESS) } returns TokenParseResult.Valid(
            userId = "testUser",
            issuedAt = Instant.parse("2026-09-10T00:00:00Z"),
            authTime = null,
            remainingExpiry = Duration.ofMinutes(30)
        )

        authService.logout("access.token", "refresh.token", LogoutRequest("fcm-1", "device1"))

        coVerify(exactly = 1) { notificationService.unregisterFcmToken("testUser", "fcm-1", "device1") }
    }

    @Test
    fun `로그아웃 - 본문이 없으면 블랙리스트만 하고 FCM은 건드리지 않는다`() = runTest {
        every { jwtProvider.getRemainingExpiry(any()) } returns Duration.ofMinutes(30)
        every { jwtProvider.blacklistKey(any()) } answers { "bl:hashed-" + firstArg<String>() }
        every { valueOps.set(any(), "1", any<Duration>()) } returns Mono.just(true)

        authService.logout("access.token", "refresh.token", null)

        coVerify(exactly = 0) { notificationService.unregisterFcmToken(any(), any(), any()) }
    }

    @Test
    fun `로그아웃 - access가 만료됐어도 refresh에서 userId를 얻어 해제한다`() = runTest {
        every { jwtProvider.getRemainingExpiry(any()) } returns Duration.ofMinutes(30)
        every { jwtProvider.blacklistKey(any()) } answers { "bl:hashed-" + firstArg<String>() }
        every { valueOps.set(any(), "1", any<Duration>()) } returns Mono.just(true)
        every { jwtProvider.parse("access.token", TokenType.ACCESS) } returns
            TokenParseResult.Invalid(ErrorCode.EXPIRED_TOKEN)
        every { jwtProvider.parse("refresh.token", TokenType.REFRESH) } returns TokenParseResult.Valid(
            userId = "testUser",
            issuedAt = Instant.parse("2026-09-10T00:00:00Z"),
            authTime = Instant.parse("2026-09-01T00:00:00Z"),
            remainingExpiry = Duration.ofDays(10)
        )

        authService.logout("access.token", "refresh.token", LogoutRequest("fcm-1", "device1"))

        coVerify(exactly = 1) { notificationService.unregisterFcmToken("testUser", "fcm-1", "device1") }
    }

    // ── 아이디 중복 확인 ──────────────────────────────

    @Test
    fun `아이디 중복 확인 - 이미 존재하는 아이디면 USER_ID_ALREADY_EXISTS 예외 발생`() = runTest {
        coEvery { userRepository.existsByUserId("testUser") } returns true

        val thrown = runCatching {
            authService.checkId(CheckIdRequest(userId = "testUser"))
        }.exceptionOrNull()

        assertTrue(thrown is BusinessException)
        assertEquals(ErrorCode.USER_ID_ALREADY_EXISTS, (thrown as BusinessException).errorCode)
    }

    // ── 액세스 토큰 검증 (자동 로그인) ───────────────────

    private fun stubValidAccess(token: String, issuedAt: Instant = Instant.parse("2026-09-10T00:00:00Z")) {
        every { jwtProvider.parse(token, TokenType.ACCESS) } returns TokenParseResult.Valid(
            userId = "testUser",
            issuedAt = issuedAt,
            authTime = null,
            remainingExpiry = Duration.ofMinutes(30)
        )
        every { jwtProvider.blacklistKey(token) } returns "bl:hashed-$token"
    }

    @Test
    fun `토큰 검증 - 토큰이 null이거나 blank면 INVALID_TOKEN`() = runTest {
        val thrown = runCatching { authService.validateAccessToken(null) }.exceptionOrNull()
        assertTrue(thrown is BusinessException)
        assertEquals(ErrorCode.INVALID_TOKEN, (thrown as BusinessException).errorCode)

        val thrownBlank = runCatching { authService.validateAccessToken("   ") }.exceptionOrNull()
        assertTrue(thrownBlank is BusinessException)
        assertEquals(ErrorCode.INVALID_TOKEN, (thrownBlank as BusinessException).errorCode)
    }

    @Test
    fun `토큰 검증 - JwtProvider 검증 실패 시 INVALID_TOKEN`() = runTest {
        every { jwtProvider.parse("bad.token", TokenType.ACCESS) } returns TokenParseResult.Invalid(ErrorCode.INVALID_TOKEN)

        val thrown = runCatching { authService.validateAccessToken("bad.token") }.exceptionOrNull()
        assertTrue(thrown is BusinessException)
        assertEquals(ErrorCode.INVALID_TOKEN, (thrown as BusinessException).errorCode)
    }

    @Test
    fun `토큰 검증 - Redis 블랙리스트에 있으면 INVALID_TOKEN`() = runTest {
        stubValidAccess("blacklisted.token")
        every { valueOps.get("bl:hashed-blacklisted.token") } returns Mono.just("1")

        val thrown = runCatching { authService.validateAccessToken("blacklisted.token") }.exceptionOrNull()
        assertTrue(thrown is BusinessException)
        assertEquals(ErrorCode.INVALID_TOKEN, (thrown as BusinessException).errorCode)
    }

    @Test
    fun `토큰 검증 - 유효 토큰 + 블랙리스트 없음 + 무효화 없음이면 예외 없이 통과`() = runTest {
        stubValidAccess("good.token")
        every { valueOps.get("bl:hashed-good.token") } returns Mono.empty()
        coEvery { tokenRevocationStore.isRevoked("testUser", Instant.parse("2026-09-10T00:00:00Z")) } returns false

        authService.validateAccessToken("good.token")
    }

    @Test
    fun `토큰 검증 - 세션이 무효화된 사용자의 토큰이면 INVALID_TOKEN (A2)`() = runTest {
        stubValidAccess("revoked.token")
        every { valueOps.get("bl:hashed-revoked.token") } returns Mono.empty()
        coEvery { tokenRevocationStore.isRevoked("testUser", any()) } returns true

        val thrown = runCatching { authService.validateAccessToken("revoked.token") }.exceptionOrNull()
        assertTrue(thrown is BusinessException)
        assertEquals(ErrorCode.INVALID_TOKEN, (thrown as BusinessException).errorCode)
    }

    // ── 이메일 중복 확인 ──────────────────────────────────────────

    @Test
    fun `이메일 중복 확인 - 이미 존재하는 이메일이면 MAIL_ALREADY_EXISTS 예외 발생`() = runTest {
        coEvery { userRepository.existsByMail("test@example.com") } returns true

        val thrown = runCatching {
            authService.checkMail(CheckMailRequest(mail = "test@example.com"))
        }.exceptionOrNull()

        assertTrue(thrown is BusinessException)
        assertEquals(ErrorCode.MAIL_ALREADY_EXISTS, (thrown as BusinessException).errorCode)
    }

    @Test
    fun `이메일 중복 확인 - 사용 가능한 이메일이면 예외 없이 통과`() = runTest {
        coEvery { userRepository.existsByMail("new@example.com") } returns false

        authService.checkMail(CheckMailRequest(mail = "new@example.com"))
    }

    // ── 보안 이벤트 기록 (C6) ────────────────────────────────────

    private fun captureEvents(): MutableList<LoginHistory> {
        val events = mutableListOf<LoginHistory>()
        coEvery { loginHistoryRepository.save(capture(events)) } answers { firstArg() }
        return events
    }

    @Test
    fun `보안 이벤트 - 로그인 성공은 LOGIN_SUCCESS로 기록된다`() = runTest {
        val events = captureEvents()
        coEvery { userRepository.findByUserId("testUser") } returns baseUser
        every { passwordEncoder.matches("pass1234", "encoded_password") } returns true
        every { jwtProvider.generateAccessToken(any(), any()) } returns "access"
        every { jwtProvider.generateRefreshToken(any(), any()) } returns "refresh"

        authService.login(LoginRequest(userId = "testUser", password = "pass1234", deviceId = "d1"), "1.2.3.4", "agent")

        assertEquals(SecurityEventType.LOGIN_SUCCESS, events.single().eventType)
        assertEquals("1.2.3.4", events.single().ipAddress)
    }

    @Test
    fun `보안 이벤트 - rate limit에 막힌 시도는 LOGIN_BLOCKED로 남고 예외는 그대로 전파된다 (C6)`() = runTest {
        val events = captureEvents()
        coEvery { rateLimiter.requireAllowed("rl:login:ip:1.2.3.4", any(), any()) } throws
            BusinessException(ErrorCode.TOO_MANY_REQUESTS)

        val thrown = runCatching {
            authService.login(LoginRequest(userId = "testUser", password = "p", deviceId = "d1"), "1.2.3.4", "agent")
        }.exceptionOrNull()

        // 차단된 시도는 자동화 공격의 첫 신호라 성공·실패보다 먼저 드러난다.
        assertEquals(ErrorCode.TOO_MANY_REQUESTS, (thrown as BusinessException).errorCode)
        assertEquals(SecurityEventType.LOGIN_BLOCKED, events.single().eventType)
        assertEquals(false, events.single().success)
    }

    @Test
    fun `보안 이벤트 - 로그아웃이 기록된다`() = runTest {
        val events = captureEvents()
        every { jwtProvider.getRemainingExpiry(any()) } returns Duration.ofMinutes(30)
        every { jwtProvider.blacklistKey(any()) } answers { "bl:hashed-" + firstArg<String>() }
        every { valueOps.set(any(), "1", any<Duration>()) } returns Mono.just(true)
        every { jwtProvider.parse("access.token", TokenType.ACCESS) } returns TokenParseResult.Valid(
            userId = "testUser",
            issuedAt = Instant.parse("2026-09-10T00:00:00Z"),
            authTime = null,
            remainingExpiry = Duration.ofMinutes(30)
        )

        authService.logout("access.token", "refresh.token", LogoutRequest("fcm-1", "device1"))

        assertEquals(SecurityEventType.LOGOUT, events.single().eventType)
        // 이 경로에는 ServerWebExchange가 닿지 않아 IP를 채우지 않는다(D36).
        assertEquals(null, events.single().ipAddress)
    }

    @Test
    fun `보안 이벤트 - 비밀번호 재설정이 PASSWORD_RESET으로 기록된다`() = runTest {
        val events = captureEvents()
        coEvery { userRepository.findByUserId("testUser") } returns baseUser
        every { valueOps.getAndDelete("reset_ticket:ticket-1") } returns Mono.just("testUser")
        coEvery { tokenRevocationStore.revokeAll("testUser") } just Runs
        every { passwordEncoder.encode(any()) } returns "encoded_new"
        coEvery { userRepository.save(any()) } answers { firstArg() }

        authService.resetPassword(
            ResetPasswordRequest(userId = "testUser", resetTicket = "ticket-1", newPassword = "newPass1234")
        )

        assertEquals(SecurityEventType.PASSWORD_RESET, events.single().eventType)
    }
}
