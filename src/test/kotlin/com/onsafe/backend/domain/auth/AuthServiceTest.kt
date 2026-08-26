package com.onsafe.backend.domain.auth

import com.onsafe.backend.common.exception.BusinessException
import com.onsafe.backend.common.exception.ErrorCode
import com.onsafe.backend.common.ratelimit.RateLimiter
import com.onsafe.backend.common.security.JwtProvider
import com.onsafe.backend.common.security.VerificationCodeGenerator
import com.onsafe.backend.domain.auth.model.dto.*
import com.onsafe.backend.domain.auth.model.entity.LoginHistory
import com.onsafe.backend.domain.auth.repository.LoginHistoryRepository
import com.onsafe.backend.domain.auth.service.AuthService
import com.onsafe.backend.domain.auth.service.EmailService
import com.onsafe.backend.domain.settings.repository.SettingsRepository
import com.onsafe.backend.domain.user.model.entity.User
import com.onsafe.backend.domain.user.repository.UserRepository
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
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
    private val rateLimiter: RateLimiter = mockk()
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
            loginHistoryRepository, settingsRepository, rateLimiter, VerificationCodeGenerator()
        )
    }

    // ── 로그인 ────────────────────────────────────────────────────

    @Test
    fun `로그인 - 존재하지 않는 아이디면 USER_NOT_FOUND 예외 발생`() = runTest {
        coEvery { userRepository.findByUserId("unknown") } returns null

        val thrown = runCatching {
            authService.login(LoginRequest(userId = "unknown", password = "pass", deviceId = "device1"), "127.0.0.1", "test-agent")
        }.exceptionOrNull()

        assertTrue(thrown is BusinessException)
        assertEquals(ErrorCode.USER_NOT_FOUND, (thrown as BusinessException).errorCode)
    }

    @Test
    fun `로그인 - 비밀번호 불일치 시 INVALID_PASSWORD 예외 발생`() = runTest {
        coEvery { userRepository.findByUserId("testUser") } returns baseUser
        every { passwordEncoder.matches("wrongPass", "encoded_password") } returns false

        val thrown = runCatching {
            authService.login(LoginRequest(userId = "testUser", password = "wrongPass", deviceId = "device1"), "127.0.0.1", "test-agent")
        }.exceptionOrNull()

        assertTrue(thrown is BusinessException)
        assertEquals(ErrorCode.INVALID_PASSWORD, (thrown as BusinessException).errorCode)
    }

    // ── 회원가입 ──────────────────────────────────────────────────

    @Test
    fun `회원가입 - 중복 아이디면 USER_ID_ALREADY_EXISTS 예외 발생`() = runTest {
        coEvery { userRepository.existsByUserId("testUser") } returns true

        val thrown = runCatching {
            authService.register(
                RegisterRequest(userId = "testUser", password = "pass1234", name = "홍길동", mail = "a@b.com", phone = "010-1234-5678")
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
                RegisterRequest(userId = "testUser", password = "pass1234", name = "홍길동", mail = "test@example.com", phone = "010-1234-5678")
            )
        }.exceptionOrNull()

        assertTrue(thrown is BusinessException)
        assertEquals(ErrorCode.MAIL_ALREADY_EXISTS, (thrown as BusinessException).errorCode)
    }

    // ── 아이디 찾기 ───────────────────────────────────────────────

    @Test
    fun `아이디 찾기 - 이메일 미존재 시 USER_NOT_FOUND 예외 발생`() = runTest {
        coEvery { userRepository.findByMail("notexist@example.com") } returns null

        val thrown = runCatching {
            authService.findId(FindIdRequest(name = "홍길동", mail = "notexist@example.com"))
        }.exceptionOrNull()

        assertTrue(thrown is BusinessException)
        assertEquals(ErrorCode.USER_NOT_FOUND, (thrown as BusinessException).errorCode)
    }

    @Test
    fun `아이디 찾기 - 이름 불일치 시 USER_NOT_FOUND 예외 발생 (이메일 존재 여부 노출 차단)`() = runTest {
        coEvery { userRepository.findByMail("test@example.com") } returns baseUser

        val thrown = runCatching {
            authService.findId(FindIdRequest(name = "다른이름", mail = "test@example.com"))
        }.exceptionOrNull()

        assertTrue(thrown is BusinessException)
        assertEquals(ErrorCode.USER_NOT_FOUND, (thrown as BusinessException).errorCode)
    }

    // ── 비밀번호 재설정 코드 발송 ──────────────────────────────────

    @Test
    fun `비밀번호 재설정 코드 발송 - 존재하지 않는 아이디면 USER_NOT_FOUND 예외 발생`() = runTest {
        coEvery { userRepository.findByUserId("unknown") } returns null

        val thrown = runCatching {
            authService.sendResetCode(SendResetCodeRequest(userId = "unknown", mail = "test@example.com"))
        }.exceptionOrNull()

        assertTrue(thrown is BusinessException)
        assertEquals(ErrorCode.USER_NOT_FOUND, (thrown as BusinessException).errorCode)
    }

    @Test
    fun `비밀번호 재설정 코드 발송 - 이메일 불일치 시 MAIL_NOT_MATCH 예외 발생`() = runTest {
        coEvery { userRepository.findByUserId("testUser") } returns baseUser

        val thrown = runCatching {
            authService.sendResetCode(SendResetCodeRequest(userId = "testUser", mail = "wrong@example.com"))
        }.exceptionOrNull()

        assertTrue(thrown is BusinessException)
        assertEquals(ErrorCode.MAIL_NOT_MATCH, (thrown as BusinessException).errorCode)
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

    // ── 비밀번호 재설정 ───────────────────────────────────────────

    @Test
    fun `비밀번호 재설정 - 인증 미완료 상태면 INVALID_RESET_CODE 예외 발생`() = runTest {
        every { valueOps.get("reset_verified:testUser") } returns Mono.empty()

        val thrown = runCatching {
            authService.resetPassword(ResetPasswordRequest(userId = "testUser", newPassword = "newPass1234"))
        }.exceptionOrNull()

        assertTrue(thrown is BusinessException)
        assertEquals(ErrorCode.INVALID_RESET_CODE, (thrown as BusinessException).errorCode)
    }

    // ── 토큰 갱신 ────────────────────────────────────────────────

    @Test
    fun `토큰 갱신 - 유효하지 않은 토큰이면 EXPIRED_TOKEN 예외 발생`() = runTest {
        every { jwtProvider.validate("invalid-token") } returns false

        val thrown = runCatching {
            authService.refresh("invalid-token")
        }.exceptionOrNull()

        assertTrue(thrown is BusinessException)
        assertEquals(ErrorCode.EXPIRED_TOKEN, (thrown as BusinessException).errorCode)
    }

    @Test
    fun `토큰 갱신 - 블랙리스트 토큰이면 INVALID_TOKEN 예외 발생`() = runTest {
        every { jwtProvider.validate("blacklisted-token") } returns true
        every { valueOps.get("bl:blacklisted-token") } returns Mono.just("1")

        val thrown = runCatching {
            authService.refresh("blacklisted-token")
        }.exceptionOrNull()

        assertTrue(thrown is BusinessException)
        assertEquals(ErrorCode.INVALID_TOKEN, (thrown as BusinessException).errorCode)
    }

    // ── FCM 토큰 갱신 ────────────────────────────────────────────

    @Test
    fun `FCM 토큰 갱신 - 존재하지 않는 아이디면 USER_NOT_FOUND 예외 발생`() = runTest {
        coEvery { userRepository.findByUserId("unknown") } returns null

        val thrown = runCatching {
            authService.updateFcmToken("unknown", "fcm-token-xyz")
        }.exceptionOrNull()

        assertTrue(thrown is BusinessException)
        assertEquals(ErrorCode.USER_NOT_FOUND, (thrown as BusinessException).errorCode)
    }

    // ── 아이디 중복 확인 ──────────────────────────────────────────

    @Test
    fun `아이디 중복 확인 - 이미 존재하는 아이디면 USER_ID_ALREADY_EXISTS 예외 발생`() = runTest {
        coEvery { userRepository.existsByUserId("testUser") } returns true

        val thrown = runCatching {
            authService.checkId(CheckIdRequest(userId = "testUser"))
        }.exceptionOrNull()

        assertTrue(thrown is BusinessException)
        assertEquals(ErrorCode.USER_ID_ALREADY_EXISTS, (thrown as BusinessException).errorCode)
    }
}
