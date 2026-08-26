package com.onsafe.backend.domain.auth.service

import com.onsafe.backend.common.exception.BusinessException
import com.onsafe.backend.common.exception.ErrorCode
import com.onsafe.backend.common.ratelimit.RateLimiter
import com.onsafe.backend.common.security.JwtProvider
import com.onsafe.backend.common.security.VerificationCodeGenerator
import com.onsafe.backend.domain.auth.model.dto.*
import com.onsafe.backend.domain.auth.model.entity.LoginHistory
import com.onsafe.backend.domain.auth.repository.LoginHistoryRepository
import com.onsafe.backend.domain.settings.model.entity.UserSettings
import com.onsafe.backend.domain.settings.repository.SettingsRepository
import com.onsafe.backend.domain.user.model.entity.User
import com.onsafe.backend.domain.user.repository.UserRepository
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactor.awaitSingle
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import java.time.Duration

private const val EMAIL_CODE_TTL = 180L    // 3분
private const val RESET_CODE_TTL = 180L    // 3분
private const val RESET_VERIFIED_TTL = 600L // 10분 — verifyResetCode 성공 후 resetPassword 가능 시간

@Service
class AuthService(
    private val userRepository: UserRepository,
    private val passwordEncoder: PasswordEncoder,
    private val jwtProvider: JwtProvider,
    private val emailService: EmailService,
    private val redis: ReactiveStringRedisTemplate,
    private val loginHistoryRepository: LoginHistoryRepository,
    private val settingsRepository: SettingsRepository,
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
            redis.opsForValue().set("bl:$token", "1", remaining).awaitSingle()
        }
    }

    suspend fun checkId(request: CheckIdRequest) {
        if (userRepository.existsByUserId(request.userId)) {
            throw BusinessException(ErrorCode.USER_ID_ALREADY_EXISTS)
        }
    }

    suspend fun sendEmailCode(request: SendEmailCodeRequest) {
        // 이메일 주소당 시간당 3회 — SES 비용 폭탄 및 인박스 스팸 방지.
        rateLimiter.requireAllowed("rl:send-email:${request.mail}", limit = 3, windowSec = 3600)
        val code = verificationCodeGenerator.generate()
        redis.opsForValue()
            .set("email_verify:${request.mail}", code, Duration.ofSeconds(EMAIL_CODE_TTL))
            .awaitSingle()
        emailService.sendEmailVerificationCode(request.mail, code)
    }

    suspend fun verifyEmailCode(request: VerifyEmailCodeRequest) {
        // 코드 브루트포스 방지 — 코드 공간이 10^6이라 창당 5회면 성공 확률이 무시할 수준.
        rateLimiter.requireAllowed("rl:verify-email:${request.mail}", limit = 5, windowSec = 3600)
        val key = "email_verify:${request.mail}"
        val storedCode = redis.opsForValue().get(key).awaitFirstOrNull()
            ?: throw BusinessException(ErrorCode.INVALID_EMAIL_CODE)
        if (storedCode != request.code) throw BusinessException(ErrorCode.INVALID_EMAIL_CODE)
        redis.delete(key).awaitSingle()
    }

    suspend fun sendResetCode(request: SendResetCodeRequest) {
        rateLimiter.requireAllowed("rl:send-reset:${request.userId}", limit = 3, windowSec = 3600)
        val user = userRepository.findByUserId(request.userId)
            ?: throw BusinessException(ErrorCode.USER_NOT_FOUND)
        if (user.mail != request.mail) throw BusinessException(ErrorCode.MAIL_NOT_MATCH)

        val code = verificationCodeGenerator.generate()
        redis.opsForValue()
            .set("reset_code:${request.userId}", code, Duration.ofSeconds(RESET_CODE_TTL))
            .awaitSingle()
        emailService.sendResetCode(request.mail, code)
    }

    suspend fun verifyResetCode(request: VerifyResetCodeRequest) {
        rateLimiter.requireAllowed("rl:verify-reset:${request.userId}", limit = 5, windowSec = 3600)
        val key = "reset_code:${request.userId}"
        val storedCode = redis.opsForValue().get(key).awaitFirstOrNull()
            ?: throw BusinessException(ErrorCode.INVALID_RESET_CODE)
        if (storedCode != request.code) throw BusinessException(ErrorCode.INVALID_RESET_CODE)
        redis.delete(key).awaitSingle()
        redis.opsForValue()
            .set("reset_verified:${request.userId}", "1", Duration.ofSeconds(RESET_VERIFIED_TTL))
            .awaitSingle()
    }

    suspend fun register(request: RegisterRequest) {
        if (userRepository.existsByUserId(request.userId)) {
            throw BusinessException(ErrorCode.USER_ID_ALREADY_EXISTS)
        }
        if (userRepository.existsByMail(request.mail)) {
            throw BusinessException(ErrorCode.MAIL_ALREADY_EXISTS)
        }
        val now = java.time.LocalDateTime.now()
        userRepository.save(
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
            )
        )
        settingsRepository.save(UserSettings(userId = request.userId))
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
        if (!jwtProvider.validate(refreshToken)) {
            throw BusinessException(ErrorCode.EXPIRED_TOKEN)
        }
        val isBlacklisted = redis.opsForValue().get("bl:$refreshToken").awaitFirstOrNull()
        if (isBlacklisted != null) throw BusinessException(ErrorCode.INVALID_TOKEN)

        val tokens = issueTokens(jwtProvider.getUserId(refreshToken), jwtProvider.getEmail(refreshToken))

        val remaining = jwtProvider.getRemainingExpiry(refreshToken)
        if (remaining > java.time.Duration.ZERO) {
            redis.opsForValue().set("bl:$refreshToken", "1", remaining).awaitSingle()
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
        redis.opsForValue().get(verifiedKey).awaitFirstOrNull()
            ?: throw BusinessException(ErrorCode.INVALID_RESET_CODE)

        val user = userRepository.findByUserId(request.userId)
            ?: throw BusinessException(ErrorCode.USER_NOT_FOUND)
        userRepository.save(user.copy(password = passwordEncoder.encode(request.newPassword)))
        redis.delete(verifiedKey).awaitSingle()
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
