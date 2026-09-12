package com.onsafe.backend.domain.auth.service

import com.onsafe.backend.common.exception.BusinessException
import com.onsafe.backend.common.exception.ErrorCode
import com.onsafe.backend.common.ratelimit.RateLimiter
import com.onsafe.backend.common.security.JwtProvider
import com.onsafe.backend.common.security.VerificationCodeGenerator
import com.onsafe.backend.domain.auth.model.dto.*
import com.onsafe.backend.domain.auth.model.entity.LoginHistory
import com.onsafe.backend.domain.auth.repository.LoginHistoryRepository
import com.onsafe.backend.domain.consent.model.entity.CURRENT_CONSENT_VERSION
import com.onsafe.backend.domain.consent.model.entity.ConsentType
import com.onsafe.backend.domain.consent.repository.ConsentRepository
import com.onsafe.backend.domain.settings.model.entity.UserSettings
import com.onsafe.backend.domain.settings.repository.SettingsRepository
import com.onsafe.backend.domain.user.model.entity.User
import com.onsafe.backend.domain.user.repository.UserRepository
import com.onsafe.backend.common.util.guardRedis
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactor.awaitSingle
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import java.time.Duration
import java.util.UUID

private const val EMAIL_CODE_TTL = 180L    // 3분
private const val RESET_CODE_TTL = 180L    // 3분
private const val RESET_VERIFIED_TTL = 600L // 10분 — verifyResetCode 성공 후 resetPassword 가능 시간
private const val EMAIL_VERIFY_TICKET_TTL = 900L // 15분 — verifyEmailCode 성공 후 register 가능 시간 (가입 폼 입력 항목이 많아 RESET_VERIFIED_TTL보다 여유를 둠)

@Service
class AuthService(
    private val userRepository: UserRepository,
    private val passwordEncoder: PasswordEncoder,
    private val jwtProvider: JwtProvider,
    private val emailService: EmailService,
    private val redis: ReactiveStringRedisTemplate,
    private val loginHistoryRepository: LoginHistoryRepository,
    private val settingsRepository: SettingsRepository,
    private val consentRepository: ConsentRepository,
    private val rateLimiter: RateLimiter,
    private val verificationCodeGenerator: VerificationCodeGenerator
) {

    private val log = LoggerFactory.getLogger(javaClass)

    // access token만 블랙리스트하면 refresh token으로 재발급이 계속 가능해 로그아웃의
    // 보안 효과가 제한적이므로, 두 토큰 모두를 각자 남은 만료 시간만큼 블랙리스트한다.
    suspend fun logout(accessToken: String?, refreshToken: String?) {
        if (!accessToken.isNullOrBlank()) blacklistToken(accessToken)
        if (!refreshToken.isNullOrBlank()) blacklistToken(refreshToken)
    }

    private suspend fun blacklistToken(token: String) {
        val remaining = jwtProvider.getRemainingExpiry(token)
        if (remaining > java.time.Duration.ZERO) {
            log.guardRedis("로그아웃 토큰 블랙리스트 저장") {
                redis.opsForValue().set(jwtProvider.blacklistKey(token), "1", remaining).awaitSingle()
            }
        }
    }

    suspend fun checkId(request: CheckIdRequest) {
        // 사전 조회 오남용 방지 — 회원가입 UX용이므로 시간당 10회로 넉넉히 허용한다.
        rateLimiter.requireAllowed("rl:check-id:${request.userId}", limit = 10, windowSec = 3600)
        if (userRepository.existsByUserId(request.userId)) {
            throw BusinessException(ErrorCode.USER_ID_ALREADY_EXISTS)
        }
    }

    suspend fun checkMail(request: CheckMailRequest) {
        // 사전 조회 오남용 방지 — 회원가입 UX용이므로 시간당 10회로 넉넉히 허용한다.
        rateLimiter.requireAllowed("rl:check-mail:${request.mail}", limit = 10, windowSec = 3600)
        if (userRepository.existsByMail(request.mail)) {
            throw BusinessException(ErrorCode.MAIL_ALREADY_EXISTS)
        }
    }

    suspend fun sendEmailCode(request: SendEmailCodeRequest) {
        // 이메일 주소당 시간당 3회 — SES 비용 폭탄 및 인박스 스팸 방지.
        rateLimiter.requireAllowed("rl:send-email:${request.mail}", limit = 3, windowSec = 3600)
        val code = verificationCodeGenerator.generate()
        log.guardRedis("email 인증코드 저장") {
            redis.opsForValue()
                .set("email_verify:${request.mail}", code, Duration.ofSeconds(EMAIL_CODE_TTL))
                .awaitSingle()
        }
        emailService.sendEmailVerificationCode(request.mail, code)
    }

    // 인증 성공 시 mail-단독 플래그 대신 UUID 티켓을 발급해 응답으로 돌려준다. register 요청은
    // 이 티켓을 첨부해야 통과되므로, 같은 mail을 다른 사용자가 훔쳐 자기 계정에 붙이는
    // 선점(squatting) 시나리오를 원천 차단한다. 티켓 값에 mail이 담겨 있어 소비 시 요청의
    // mail 필드와 대조해 티켓·mail 불일치도 걸러낸다.
    suspend fun verifyEmailCode(request: VerifyEmailCodeRequest): VerifyEmailCodeResponse {
        // 코드 브루트포스 방지 — 코드 공간이 10^6이라 창당 5회면 성공 확률이 무시할 수준.
        rateLimiter.requireAllowed("rl:verify-email:${request.mail}", limit = 5, windowSec = 3600)
        val key = "email_verify:${request.mail}"
        val storedCode = log.guardRedis("email 인증코드 조회") { redis.opsForValue().get(key).awaitFirstOrNull() }
            ?: throw BusinessException(ErrorCode.INVALID_EMAIL_CODE)
        if (storedCode != request.code) throw BusinessException(ErrorCode.INVALID_EMAIL_CODE)

        val ticket = UUID.randomUUID().toString()
        log.guardRedis("email 인증코드 삭제 및 티켓 저장") {
            redis.delete(key).awaitSingle()
            redis.opsForValue()
                .set("verify_ticket:$ticket", request.mail, Duration.ofSeconds(EMAIL_VERIFY_TICKET_TTL))
                .awaitSingle()
        }
        return VerifyEmailCodeResponse(emailVerifyTicket = ticket)
    }

    suspend fun sendResetCode(request: SendResetCodeRequest) {
        rateLimiter.requireAllowed("rl:send-reset:${request.userId}", limit = 3, windowSec = 3600)
        val user = userRepository.findByUserId(request.userId)
            ?: throw BusinessException(ErrorCode.USER_NOT_FOUND)
        if (user.mail != request.mail) throw BusinessException(ErrorCode.MAIL_NOT_MATCH)

        val code = verificationCodeGenerator.generate()
        log.guardRedis("reset 인증코드 저장") {
            redis.opsForValue()
                .set("reset_code:${request.userId}", code, Duration.ofSeconds(RESET_CODE_TTL))
                .awaitSingle()
        }
        emailService.sendResetCode(request.mail, code)
    }

    suspend fun verifyResetCode(request: VerifyResetCodeRequest) {
        rateLimiter.requireAllowed("rl:verify-reset:${request.userId}", limit = 5, windowSec = 3600)
        val key = "reset_code:${request.userId}"
        val storedCode = log.guardRedis("reset 인증코드 조회") { redis.opsForValue().get(key).awaitFirstOrNull() }
            ?: throw BusinessException(ErrorCode.INVALID_RESET_CODE)
        if (storedCode != request.code) throw BusinessException(ErrorCode.INVALID_RESET_CODE)
        log.guardRedis("reset 인증코드 삭제 및 완료 플래그 저장") {
            redis.delete(key).awaitSingle()
            redis.opsForValue()
                .set("reset_verified:${request.userId}", "1", Duration.ofSeconds(RESET_VERIFIED_TTL))
                .awaitSingle()
        }
    }

    suspend fun register(request: RegisterRequest, ipAddress: String) {
        // 이메일 인증 티켓만으로는 이미 인증된 티켓을 재사용한 반복 register() 호출까지는 막지
        // 못해 IP 기준으로 한 번 더 제한한다.
        rateLimiter.requireAllowed("rl:register:ip:$ipAddress", limit = 10, windowSec = 3600)
        if (userRepository.existsByUserId(request.userId)) {
            throw BusinessException(ErrorCode.USER_ID_ALREADY_EXISTS)
        }
        if (userRepository.existsByMail(request.mail)) {
            throw BusinessException(ErrorCode.MAIL_ALREADY_EXISTS)
        }
        if (userRepository.existsByPhone(request.phone)) {
            throw BusinessException(ErrorCode.PHONE_ALREADY_EXISTS)
        }
        // 티켓을 GETDEL로 원자적으로 소비 — 동시 요청이 같은 티켓을 재사용하려 해도 한 요청만
        // 성공한다. 티켓 값(=verify 시점의 mail)이 이 요청의 mail과 일치해야만 인증으로 인정 —
        // 이렇게 하지 않으면 인증만 마친 다른 사용자의 이메일을 자기 계정에 붙일 수 있다.
        val ticketMail = log.guardRedis("email 인증 티켓 소비") {
            redis.opsForValue().getAndDelete("verify_ticket:${request.emailVerifyTicket}").awaitFirstOrNull()
        } ?: throw BusinessException(ErrorCode.EMAIL_NOT_VERIFIED)
        if (ticketMail != request.mail) {
            throw BusinessException(ErrorCode.EMAIL_NOT_VERIFIED)
        }

        val now = java.time.LocalDateTime.now()
        // 위 existsByUserId/Mail/Phone 사전 체크는 흔한 경우(이미 존재)를 빠르게 걸러내는 역할,
        // 실제 동시 요청 레이스는 여기 createIfNotExists의 트랜잭션이 최종 방어한다.
        // users + user_emails/{mail} + user_phones/{phone} + settings + consents를 한 트랜잭션으로
        // 묶어 세 축의 유일성과 부분 성공 방지를 동시에 처리한다.
        val created = userRepository.createIfNotExists(
            User(
                userId = request.userId,
                password = passwordEncoder.encode(request.password),
                name = request.name,
                phone = request.phone,
                mail = request.mail,
                address = request.address,
                addressDetail = request.addressDetail,
                marketingConsent = request.marketingConsent,
                marketingConsentAt = if (request.marketingConsent) now else null,
                marketingConsentWithdrawnAt = null,
            ),
            additionalWrites = listOf(settingsRepository.buildCreateWrite(UserSettings(userId = request.userId))) +
                consentRepository.buildCreateWrites(
                    userId = request.userId,
                    types = listOf(ConsentType.TERMS_OF_SERVICE, ConsentType.PRIVACY_POLICY, ConsentType.SENSITIVE_INFO),
                    version = CURRENT_CONSENT_VERSION,
                    agreedAt = now
                )
        )
        // 트랜잭션 실패는 세 축(userId/mail/phone) 중 하나가 사이 창에 저장됐다는 뜻. 사용자가
        // 어떤 축을 바꿔야 할지 알 수 있도록 어느 축이 이미 존재하는지 재조회해서 세분화된
        // 에러를 던진다. 이 시점의 사후 조회 자체는 새 레이스에 열려 있지만 안내 목적일 뿐,
        // 실제 데이터 무결성은 이미 트랜잭션이 보장한 상태다.
        if (!created) {
            when {
                userRepository.existsByUserId(request.userId) -> throw BusinessException(ErrorCode.USER_ID_ALREADY_EXISTS)
                userRepository.existsByMail(request.mail) -> throw BusinessException(ErrorCode.MAIL_ALREADY_EXISTS)
                userRepository.existsByPhone(request.phone) -> throw BusinessException(ErrorCode.PHONE_ALREADY_EXISTS)
                else -> throw BusinessException(ErrorCode.USER_ID_ALREADY_EXISTS)
            }
        }
        // 티켓은 위에서 GETDEL로 이미 소비돼 별도 정리 불필요.
    }

    suspend fun login(request: LoginRequest, ipAddress: String, userAgent: String): LoginResponse {
        // 이중 rate-limit: IP는 자동화 도구 봇넷 대응, userId는 특정 계정 표적 브루트포스 대응.
        // 실제 사용자는 두 창을 동시에 넘길 일이 거의 없으므로 정상 트래픽에 영향 없음.
        rateLimiter.requireAllowed("rl:login:ip:$ipAddress", limit = 10, windowSec = 60)
        rateLimiter.requireAllowed("rl:login:uid:${request.userId}", limit = 5, windowSec = 60)
        val user = userRepository.findByUserId(request.userId)
        if (user == null) {
            recordLoginHistory(request.userId, ipAddress, userAgent, false, ErrorCode.USER_NOT_FOUND.name)
            throw BusinessException(ErrorCode.USER_NOT_FOUND)
        }

        if (!passwordEncoder.matches(request.password, user.password)) {
            recordLoginHistory(user.userId, ipAddress, userAgent, false, ErrorCode.INVALID_PASSWORD.name)
            throw BusinessException(ErrorCode.INVALID_PASSWORD)
        }

        recordLoginHistory(user.userId, ipAddress, userAgent, true, null)
        val tokens = issueTokens(user.userId, user.mail)
        return LoginResponse(
            userId = user.userId,
            deviceId = request.deviceId,
            name = user.name,
            accessToken = tokens.accessToken,
            refreshToken = tokens.refreshToken
        )
    }

    private suspend fun recordLoginHistory(
        userId: String,
        ipAddress: String,
        userAgent: String,
        success: Boolean,
        failReason: String?
    ) {
        // 이력 저장 실패는 로그인 자체를 막지 않되(사용자 경험 우선),
        // 침입 시도 감지·사후 조사를 위해 실패 사실은 반드시 error 로그로 남긴다.
        runCatching {
            loginHistoryRepository.save(
                LoginHistory(
                    historyId = "",
                    userId = userId,
                    ipAddress = ipAddress,
                    userAgent = userAgent,
                    success = success,
                    failReason = failReason
                )
            )
        }.onFailure { e ->
            log.error(
                "로그인 이력 저장 실패 — userId={}, success={}, failReason={}, cause={}",
                userId, success, failReason, e.message, e
            )
        }
    }

    suspend fun refresh(refreshToken: String): TokenResponse {
        jwtProvider.getValidationError(refreshToken)?.let { throw BusinessException(it) }
        val isBlacklisted = log.guardRedis("refresh 토큰 블랙리스트 조회") {
            redis.opsForValue().get(jwtProvider.blacklistKey(refreshToken)).awaitFirstOrNull()
        }
        if (isBlacklisted != null) throw BusinessException(ErrorCode.INVALID_TOKEN)

        val tokens = issueTokens(jwtProvider.getUserId(refreshToken), jwtProvider.getEmail(refreshToken))

        val remaining = jwtProvider.getRemainingExpiry(refreshToken)
        if (remaining > java.time.Duration.ZERO) {
            log.guardRedis("refresh 토큰 블랙리스트 저장") {
                redis.opsForValue().set(jwtProvider.blacklistKey(refreshToken), "1", remaining).awaitSingle()
            }
        }
        return tokens
    }

    suspend fun findId(request: FindIdRequest): FindIdResponse {
        val user = userRepository.findByMail(request.mail)
            ?: throw BusinessException(ErrorCode.USER_NOT_FOUND)
        if (user.name != request.name) throw BusinessException(ErrorCode.USER_NOT_FOUND)
        return FindIdResponse(userId = maskUserId(user.userId))
    }

    suspend fun resetPassword(request: ResetPasswordRequest) {
        val verifiedKey = "reset_verified:${request.userId}"
        log.guardRedis("reset 완료 플래그 조회") { redis.opsForValue().get(verifiedKey).awaitFirstOrNull() }
            ?: throw BusinessException(ErrorCode.INVALID_RESET_CODE)

        val user = userRepository.findByUserId(request.userId)
            ?: throw BusinessException(ErrorCode.USER_NOT_FOUND)
        userRepository.save(user.copy(password = passwordEncoder.encode(request.newPassword)))
        log.guardRedis("reset 완료 플래그 삭제") { redis.delete(verifiedKey).awaitSingle() }
    }

    suspend fun updateFcmToken(userId: String, fcmToken: String) {
        val user = userRepository.findByUserId(userId)
            ?: throw BusinessException(ErrorCode.USER_NOT_FOUND)
        userRepository.save(user.copy(fcmToken = fcmToken))
    }

    private fun issueTokens(userId: String, mail: String) = TokenResponse(
        accessToken = jwtProvider.generateAccessToken(userId, mail),
        refreshToken = jwtProvider.generateRefreshToken(userId, mail)
    )

    private fun maskUserId(userId: String): String {
        if (userId.length <= 3) return userId
        return userId.take(3) + "*".repeat(userId.length - 3)
    }
}
