package com.onsafe.backend.domain.auth.service

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
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.UUID

private const val MAX_USER_AGENT_LENGTH = 512 // 로그인 이력에 저장할 User-Agent 최대 길이(D4)
private const val EMAIL_CODE_TTL = 180L    // 3분
private const val RESET_CODE_TTL = 180L    // 3분
private const val RESET_TICKET_TTL = 600L // 10분 — verifyResetCode가 발급한 재설정 티켓의 수명
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
    private val verificationCodeGenerator: VerificationCodeGenerator,
    private val tokenRevocationStore: TokenRevocationStore
) {

    private val log = LoggerFactory.getLogger(javaClass)

    // 인증코드 비교는 상수 시간으로 한다(D2). `!=`는 첫 불일치 문자에서 즉시 빠져나와 응답 시간에
    // 일치한 자릿수가 드러난다. 코드가 6자리 고정이라 길이 차이로 새는 정보는 없다.
    private fun codeMatches(stored: String, input: String): Boolean =
        MessageDigest.isEqual(stored.toByteArray(Charsets.UTF_8), input.toByteArray(Charsets.UTF_8))

    // 검증 실패를 그대로 BusinessException으로 올린다 — 만료(EXPIRED)와 무효(INVALID)를 구분해
    // 던져야 클라이언트가 refresh 시도 vs 강제 로그아웃을 나눠 처리할 수 있다.
    // 없는 아이디일 때도 비교할 대상이 필요하다(B3). 매번 새로 만들면 그것만큼 느려져
    // 오히려 시간차가 생기므로, 처음 한 번만 만들어 둔다. 값 자체는 쓰이지 않고
    // BCrypt 비교에 걸리는 시간만 필요하다. 기동 시간을 늘리지 않게 lazy로 둔다.
    private val dummyPasswordHash: String by lazy { passwordEncoder.encode(UUID.randomUUID().toString()) }

    private fun TokenParseResult.valueOrThrow(): TokenParseResult.Valid = when (this) {
        is TokenParseResult.Valid -> this
        is TokenParseResult.Invalid -> throw BusinessException(errorCode)
    }

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

    // 자동 로그인 진입 전 서버 검증용 — 로컬 30일 제한만으로는 회원탈퇴·강제로그아웃 후에도
    // 로컬 토큰이 살아있으면 진입이 가능해지므로, 만료/서명뿐 아니라 Redis 블랙리스트도 확인한다.
    // 만료(EXPIRED)와 서명 무효(INVALID)를 그대로 구분해 던져야 클라이언트가 refresh 시도 vs
    // 강제 로그아웃을 나눠 처리할 수 있다 — 뭉개면 30일 자동로그인 정책이 access token 만료
    // 주기(1시간)마다 사실상 리셋된다.
    suspend fun validateAccessToken(accessToken: String?) {
        if (accessToken.isNullOrBlank()) throw BusinessException(ErrorCode.INVALID_TOKEN)
        val parsed = jwtProvider.parse(accessToken, TokenType.ACCESS).valueOrThrow()
        val blacklisted = log.guardRedis("access token 블랙리스트 조회") {
            redis.opsForValue().get(jwtProvider.blacklistKey(accessToken)).awaitFirstOrNull()
        }
        if (blacklisted != null) throw BusinessException(ErrorCode.INVALID_TOKEN)
        // 탈퇴·비밀번호 변경·재설정으로 끊긴 세션이면 자동 로그인도 막는다.
        if (tokenRevocationStore.isRevoked(parsed.userId, parsed.issuedAt)) {
            throw BusinessException(ErrorCode.INVALID_TOKEN)
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
        if (!codeMatches(storedCode, request.code)) throw BusinessException(ErrorCode.INVALID_EMAIL_CODE)

        val ticket = UUID.randomUUID().toString()
        log.guardRedis("email 인증코드 삭제 및 티켓 저장") {
            redis.delete(key).awaitSingle()
            redis.opsForValue()
                .set("verify_ticket:$ticket", request.mail, Duration.ofSeconds(EMAIL_VERIFY_TICKET_TTL))
                .awaitSingle()
        }
        return VerifyEmailCodeResponse(emailVerifyTicket = ticket)
    }

    /**
     * 아이디·메일이 맞지 않아도 **같은 성공 응답**을 준다(C2). 이전에는 없는 아이디 404, 메일 불일치 400이라
     * 응답만으로 가입 여부와 그 계정의 메일 일치 여부를 확인할 수 있었다. 대신 메일은 보내지 않는다.
     * IP 제한을 함께 둬, 한 IP에서 여러 계정을 대상으로 돌려보는 시도를 막는다(userId 제한만으로는 못 막는다).
     */
    suspend fun sendResetCode(request: SendResetCodeRequest, ipAddress: String) {
        rateLimiter.requireAllowed("rl:send-reset:${request.userId}", limit = 3, windowSec = 3600)
        rateLimiter.requireAllowed("rl:send-reset:ip:$ipAddress", limit = 10, windowSec = 3600)
        val user = userRepository.findByUserId(request.userId)
        // 존재 여부·일치 여부를 응답으로 구분하지 않으므로 여기서 조용히 끝낸다. 실패 사유 기록은 C6(9단계).
        if (user == null || user.mail != request.mail) return

        val code = verificationCodeGenerator.generate()
        log.guardRedis("reset 인증코드 저장") {
            redis.opsForValue()
                .set("reset_code:${request.userId}", code, Duration.ofSeconds(RESET_CODE_TTL))
                .awaitSingle()
        }
        emailService.sendResetCode(request.mail, code)
    }

    /**
     * 인증 성공 시 `reset_verified:{userId}` 플래그 대신 **UUID 티켓**을 발급해 응답으로 돌려준다(A3).
     * 플래그 방식은 "이 userId가 인증을 마쳤다"는 사실만 남아, 인증한 사람과 재설정하는 사람이 같은지
     * 확인하지 못했다 — userId만 알면 10분 안에 누구나 비밀번호를 바꿀 수 있었다.
     * 가입 흐름의 이메일 인증 티켓(`verify_ticket:{uuid}`)과 같은 구조다.
     */
    suspend fun verifyResetCode(request: VerifyResetCodeRequest): VerifyResetCodeResponse {
        rateLimiter.requireAllowed("rl:verify-reset:${request.userId}", limit = 5, windowSec = 3600)
        val key = "reset_code:${request.userId}"
        val storedCode = log.guardRedis("reset 인증코드 조회") { redis.opsForValue().get(key).awaitFirstOrNull() }
            ?: throw BusinessException(ErrorCode.INVALID_RESET_CODE)
        if (!codeMatches(storedCode, request.code)) throw BusinessException(ErrorCode.INVALID_RESET_CODE)

        val ticket = UUID.randomUUID().toString()
        log.guardRedis("reset 인증코드 삭제 및 티켓 저장") {
            redis.delete(key).awaitSingle()
            redis.opsForValue()
                .set("reset_ticket:$ticket", request.userId, Duration.ofSeconds(RESET_TICKET_TTL))
                .awaitSingle()
        }
        return VerifyResetCodeResponse(resetTicket = ticket)
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
            // 응답 코드를 LOGIN_FAILED로 맞춰도 BCrypt 비교를 건너뛰면 응답이 그만큼 빨라
            // **응답 시간으로 가입 여부가 드러난다.** 더미 해시와 비교해 걸리는 시간을 맞춘다(B3).
            passwordEncoder.matches(request.password, dummyPasswordHash)
            recordLoginHistory(request.userId, ipAddress, userAgent, false, ErrorCode.USER_NOT_FOUND.name)
            throw BusinessException(ErrorCode.LOGIN_FAILED)
        }

        if (!passwordEncoder.matches(request.password, user.password)) {
            // 실패 사유(INVALID_PASSWORD)는 이력에만 남기고 응답은 LOGIN_FAILED(401)로 뭉뚱그린다.
            // INVALID_PASSWORD는 B7에서 400이 됐는데, 로그인 실패까지 400이면 앱이 토큰 흐름과
            // 무관한 실패를 다르게 다뤄야 한다. 아이디 존재 여부 은폐(B3 나머지)는 5단계.
            recordLoginHistory(user.userId, ipAddress, userAgent, false, ErrorCode.INVALID_PASSWORD.name)
            throw BusinessException(ErrorCode.LOGIN_FAILED)
        }

        recordLoginHistory(user.userId, ipAddress, userAgent, true, null)
        val tokens = issueTokens(user.userId, authTime = Instant.now())
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
                    // 길이 제한 없이 저장하면 클라이언트가 긴 User-Agent를 보내 Firestore 문서를 부풀릴 수 있다(D4).
                    // 모든 이력 저장이 이 함수를 거치므로 여기서만 자른다.
                    userAgent = userAgent.take(MAX_USER_AGENT_LENGTH),
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
        val parsed = jwtProvider.parse(refreshToken, TokenType.REFRESH).valueOrThrow()
        val userId = parsed.userId

        // 사용자 단위로 끊긴 세션(탈퇴·비밀번호 변경·재설정)이면 재발급하지 않는다.
        if (tokenRevocationStore.isRevoked(userId, parsed.issuedAt)) {
            throw BusinessException(ErrorCode.INVALID_TOKEN)
        }
        // 무효화 키는 TTL(30일) 뒤 사라지므로, 탈퇴한 계정은 존재 여부로도 한 번 더 막는다.
        userRepository.findByUserId(userId) ?: throw BusinessException(ErrorCode.INVALID_TOKEN)

        // 조회 → 발급 → 블랙리스트 등록이 분리돼 있으면, 같은 refresh 토큰의 동시 요청이 둘 다 성공해
        // 세션이 갈라진다. 발급 직전에 블랙리스트 키를 SET NX로 선점해 선점한 요청 하나만 발급한다.
        // 키가 이미 있으면 로그아웃·이전 재발급·동시 요청 중 하나다. 앞의 검사에서 실패하면 선점하지
        // 않으므로 Firestore 장애 같은 일시 오류로 토큰이 소모되지 않는다.
        val remaining = parsed.remainingExpiry
        if (remaining <= Duration.ZERO) throw BusinessException(ErrorCode.EXPIRED_TOKEN) // 검증 직후 만료된 경계
        val claimed = log.guardRedis("refresh 토큰 선점") {
            redis.opsForValue().setIfAbsent(jwtProvider.blacklistKey(refreshToken), "1", remaining).awaitSingle()
        }
        if (!claimed) throw BusinessException(ErrorCode.INVALID_TOKEN)

        // 로그인 시각(auth_time)을 이어받아 새 토큰도 로그인 기준 30일에 만료되게 한다.
        // refresh 토큰은 검증에서 auth_time 존재를 보장하지만(JwtProvider.hasExpectedShape) 타입상 nullable이다.
        val authTime = parsed.authTime ?: throw BusinessException(ErrorCode.INVALID_TOKEN)
        return issueTokens(userId, authTime)
    }

    /**
     * 메일 소유를 증명한 요청만 받는다(C1). 이전에는 앱 화면에서만 인증을 강제해,
     * API를 직접 호출하면 이름+메일 조합 대입으로 계정 존재 여부와 마스킹된 아이디를 얻을 수 있었다.
     * 티켓은 가입(`register`)과 같은 `verify_ticket:{uuid}`다 — "이 메일의 소유자"라는 뜻이라
     * 용도를 구분할 필요가 없다(작업 문서 §11-2).
     */
    suspend fun findId(request: FindIdRequest, ipAddress: String): FindIdResponse {
        rateLimiter.requireAllowed("rl:find-id:ip:$ipAddress", limit = 10, windowSec = 3600)

        // 조회를 먼저 하되 판단은 티켓 검증 뒤에 한다 — 순서를 바꾸면 Firestore 일시 오류로
        // 티켓만 타 버리고(D15와 같은 이유), 반대로 조회 결과를 먼저 던지면 티켓 없이도 가입 여부가 새다.
        val user = userRepository.findByMail(request.mail)

        // 티켓을 GETDEL로 1회 소비하고, 티켓에 담긴 mail과 요청의 mail이 같아야 인증으로 인정한다.
        val ticketMail = log.guardRedis("아이디 찾기 인증 티켓 소비") {
            redis.opsForValue().getAndDelete("verify_ticket:${request.emailVerifyTicket}").awaitFirstOrNull()
        } ?: throw BusinessException(ErrorCode.EMAIL_NOT_VERIFIED)
        if (ticketMail != request.mail) throw BusinessException(ErrorCode.EMAIL_NOT_VERIFIED)

        if (user == null || user.name != request.name) throw BusinessException(ErrorCode.USER_NOT_FOUND)
        return FindIdResponse(userId = maskUserId(user.userId))
    }

    suspend fun resetPassword(request: ResetPasswordRequest) {
        // 사용자 조회를 먼저 한다 — 티켓은 저장 직전에 소비해야 Firestore 일시 오류로 티켓이 타 버려
        // 인증코드 발송부터 다시 하는 일이 없다(2단계 B4 선점과 같은 방침).
        // 없는 사용자도 INVALID_RESET_CODE로 돌려준다 — USER_NOT_FOUND를 주면 티켓 없이도
        // 아이디 존재 여부를 확인할 수 있어 C2로 막은 통로가 여기로 다시 열린다.
        val user = userRepository.findByUserId(request.userId)
            ?: throw BusinessException(ErrorCode.INVALID_RESET_CODE)

        // 티켓을 GETDEL로 원자적으로 소비 — 동시 요청이 같은 티켓을 재사용하려 해도 한 요청만 통과한다.
        val ticketKey = "reset_ticket:${request.resetTicket}"
        val ticketUserId = log.guardRedis("reset 티켓 소비") {
            redis.opsForValue().getAndDelete(ticketKey).awaitFirstOrNull()
        } ?: throw BusinessException(ErrorCode.INVALID_RESET_CODE)
        // 티켓에 담긴 userId와 요청의 userId가 다르면 남의 계정을 바꾸려는 요청이다.
        if (ticketUserId != request.userId) throw BusinessException(ErrorCode.INVALID_RESET_CODE)

        // 비밀번호를 잊었거나 탈취가 의심돼 재설정하는 경우라 기존 세션을 모두 끊는다.
        // 저장보다 먼저 한다 — 저장 후 무효화가 실패하면 비밀번호만 바뀌고 옛 세션이 남는다.
        tokenRevocationStore.revokeAll(user.userId)
        userRepository.save(user.copy(password = passwordEncoder.encode(request.newPassword)))
    }

    suspend fun updateFcmToken(userId: String, fcmToken: String) {
        val user = userRepository.findByUserId(userId)
            ?: throw BusinessException(ErrorCode.USER_NOT_FOUND)
        userRepository.save(user.copy(fcmToken = fcmToken))
    }

    private fun issueTokens(userId: String, authTime: Instant) = TokenResponse(
        accessToken = jwtProvider.generateAccessToken(userId, authTime),
        refreshToken = jwtProvider.generateRefreshToken(userId, authTime)
    )

    private fun maskUserId(userId: String): String {
        if (userId.length <= 3) return userId
        return userId.take(3) + "*".repeat(userId.length - 3)
    }
}
