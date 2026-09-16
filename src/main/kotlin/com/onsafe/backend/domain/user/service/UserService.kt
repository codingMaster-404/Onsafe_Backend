package com.onsafe.backend.domain.user.service

import com.onsafe.backend.common.exception.BusinessException
import com.onsafe.backend.common.exception.ErrorCode
import com.onsafe.backend.common.ratelimit.RateLimiter
import com.onsafe.backend.common.security.TokenRevocationStore
import com.onsafe.backend.common.storage.StorageService
import com.onsafe.backend.domain.auth.repository.LoginHistoryRepository
import com.onsafe.backend.domain.camera.repository.RealtimeDataRepository
import com.onsafe.backend.domain.consent.repository.ConsentRepository
import com.onsafe.backend.domain.guardian.repository.GuardianLinkRepository
import com.onsafe.backend.domain.logs.repository.FallLogRepository
import com.onsafe.backend.domain.notification.repository.FcmTokenRepository
import com.onsafe.backend.domain.notification.repository.NotificationRepository
import com.onsafe.backend.domain.settings.repository.SettingsRepository
import com.onsafe.backend.common.util.guardRedis
import com.onsafe.backend.domain.user.model.dto.UserResponse
import com.onsafe.backend.domain.user.model.dto.VerifyPasswordResponse
import com.onsafe.backend.domain.user.model.dto.UserUpdateRequest
import com.onsafe.backend.domain.user.repository.UserRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import java.time.Duration
import java.util.UUID

// verify-password 성공 후 개인정보 수정·탈퇴까지 허용하는 시간. 재설정 티켓(RESET_TICKET_TTL)과 같은 10분 —
// 길수록 탈취된 티켓의 유효 시간이 길어진다.
private const val REAUTH_TICKET_TTL = 600L

@Service
class UserService(
    private val userRepository: UserRepository,
    private val settingsRepository: SettingsRepository,
    private val passwordEncoder: PasswordEncoder,
    private val fallLogRepository: FallLogRepository,
    private val loginHistoryRepository: LoginHistoryRepository,
    private val realtimeDataRepository: RealtimeDataRepository,
    private val storageService: StorageService,
    private val notificationRepository: NotificationRepository,
    private val guardianLinkRepository: GuardianLinkRepository,
    private val consentRepository: ConsentRepository,
    private val tokenRevocationStore: TokenRevocationStore,
    private val fcmTokenRepository: FcmTokenRepository,
    private val rateLimiter: RateLimiter,
    private val redis: ReactiveStringRedisTemplate
) {

    private val log = LoggerFactory.getLogger(javaClass)

    suspend fun getUser(userId: String): UserResponse {
        val user = userRepository.findByUserId(userId)
            ?: throw BusinessException(ErrorCode.USER_NOT_FOUND)
        return UserResponse.from(user)
    }

    /**
     * 개인정보 수정(B1).
     *
     * 이전에는 비밀번호를 바꿀 때만 `current_password`를 확인했고, 이름·메일·전화·주소는 **아무 확인 없이**
     * 저장됐다. 앱은 수정 화면 진입 전 `verify-password`를 부르지만 저장 요청에는 그 사실이 담기지 않아
     * 서버가 알 수 없었다 — 그래서 재인증 티켓을 받는다.
     *
     * 메일·전화는 값이 **실제로 바뀐 경우에만** 인증·중복 검사를 한다. 앱이 바꾸지 않은 항목도 현재 값을
     * 그대로 실어 보내기 때문에, 필드 존재만 보고 판단하면 이름만 고쳐도 저장이 막힌다.
     */
    suspend fun updateUser(userId: String, request: UserUpdateRequest): UserResponse {
        val user = userRepository.findByUserId(userId)
            ?: throw BusinessException(ErrorCode.USER_NOT_FOUND)

        val changingPassword = request.password != null
        val passwordVerified = changingPassword && request.currentPassword != null &&
            passwordEncoder.matches(request.currentPassword, user.password)
        val reauthVerified = isReauthVerified(request.reauthTicket, userId)
        // 본인 확인 수단은 둘 중 하나면 된다 — 티켓(수정 화면) 또는 현재 비밀번호(비밀번호 변경 화면).
        if (!reauthVerified && !passwordVerified) {
            throw BusinessException(
                if (changingPassword) ErrorCode.INVALID_PASSWORD else ErrorCode.REAUTH_REQUIRED
            )
        }

        val newMail = request.mail?.takeIf { !it.equals(user.mail, ignoreCase = true) }
        if (newMail != null) {
            // 메일 소유 확인 — 가입·아이디 찾기와 같은 티켓을 1회 소비한다(D18과 같은 방침).
            val ticketMail = log.guardRedis("메일 변경 인증 티켓 소비") {
                redis.opsForValue().getAndDelete("verify_ticket:${request.emailVerifyTicket}").awaitFirstOrNull()
            } ?: throw BusinessException(ErrorCode.EMAIL_NOT_VERIFIED)
            if (!ticketMail.equals(newMail, ignoreCase = true)) {
                throw BusinessException(ErrorCode.EMAIL_NOT_VERIFIED)
            }
        }

        if (changingPassword) {
            // 비밀번호를 바꾸면 이 기기를 포함한 모든 세션을 끊는다(완료 문서 D6 — 새 토큰은 발급하지 않고
            // 앱이 재로그인 화면으로 보낸다). 저장보다 먼저 한다 — 저장 후 무효화가 실패하면 비밀번호만
            // 바뀌고 탈취자의 옛 세션이 남는다. 반대로 무효화 후 저장이 실패하면 재로그인 1회로 끝난다.
            tokenRevocationStore.revokeAll(userId)
        }

        val updated = user.copy(
            name = request.name ?: user.name,
            password = if (request.password != null) passwordEncoder.encode(request.password) else user.password,
            mail = request.mail ?: user.mail,
            phone = request.phone ?: user.phone,
            address = request.address ?: user.address,
            addressDetail = request.addressDetail ?: user.addressDetail
        )
        // 메일·전화 룩업 이동까지 한 트랜잭션으로 — 다른 계정이 쓰는 값이면 409.
        val saved = userRepository.saveWithLookups(user, updated)
        if (!saved) {
            throw BusinessException(
                if (newMail != null) ErrorCode.MAIL_ALREADY_EXISTS else ErrorCode.PHONE_ALREADY_EXISTS
            )
        }
        // 티켓은 저장이 끝난 뒤에 지운다 — 앞에서 소비하면 전화번호 중복(409)이나 Firestore 일시 오류로
        // 실패했을 때 티켓만 타 버려, 값 하나 고쳐 다시 저장하려 해도 비밀번호부터 다시 확인해야 한다(D15와 같은 이유).
        if (reauthVerified) deleteReauthTicket(request.reauthTicket)
        return UserResponse.from(updated)
    }

    /**
     * 티켓이 유효하고 그 주인이 요청자인지 **확인만** 한다(삭제하지 않음).
     * 삭제는 저장 성공 후 [deleteReauthTicket]이 맡는다 — 실패한 요청이 티켓을 태우지 않게.
     * 확인과 삭제가 나뉘어 10분 안에 같은 티켓으로 여러 번 수정할 수는 있지만, 이 티켓은 "본인이
     * 방금 비밀번호를 확인했다"는 뜻이라 1회성보다 재시도 가능성이 중요하다(재설정 티켓과 다른 점).
     */
    private suspend fun isReauthVerified(ticket: String?, userId: String): Boolean {
        val key = ticket?.takeIf { it.isNotBlank() } ?: return false
        val ticketUserId = log.guardRedis("재인증 티켓 확인") {
            redis.opsForValue().get("reauth_ticket:$key").awaitFirstOrNull()
        }
        return ticketUserId == userId
    }

    // 삭제 실패는 요청을 실패시키지 않는다 — 남아도 TTL(10분)로 사라진다.
    private suspend fun deleteReauthTicket(ticket: String?) {
        val key = ticket?.takeIf { it.isNotBlank() } ?: return
        runCatching { redis.delete("reauth_ticket:$key").awaitSingle() }
            .onFailure { e -> log.warn("재인증 티켓 삭제 실패 — cause={}", e.message) }
    }

    /**
     * 비밀번호 사전 확인(B5) — 성공하면 재인증 티켓을 발급한다(B1).
     *
     * rate limit이 없으면 탈취한 access 토큰으로 비밀번호를 무제한 대입할 수 있었다.
     */
    suspend fun verifyPassword(userId: String, currentPassword: String): VerifyPasswordResponse {
        rateLimiter.requireAllowed("rl:verify-password:$userId", limit = 5, windowSec = 600)
        val user = userRepository.findByUserId(userId)
            ?: throw BusinessException(ErrorCode.USER_NOT_FOUND)
        if (!passwordEncoder.matches(currentPassword, user.password)) {
            throw BusinessException(ErrorCode.INVALID_PASSWORD)
        }

        val ticket = UUID.randomUUID().toString()
        log.guardRedis("재인증 티켓 저장") {
            redis.opsForValue()
                .set("reauth_ticket:$ticket", userId, Duration.ofSeconds(REAUTH_TICKET_TTL))
                .awaitSingle()
        }
        return VerifyPasswordResponse(reauthTicket = ticket)
    }

    suspend fun deleteUser(userId: String) {
        // findByUserId를 앞에서 한 번만 수행 — 아래 cascade에서 user_emails/{mail},
        // user_phones/{phone} 룩업 문서를 지우려면 mail/phone 값이 필요하다.
        val user = userRepository.findByUserId(userId)
            ?: throw BusinessException(ErrorCode.USER_NOT_FOUND)
        // 삭제를 시작하기 전에 모든 세션을 끊는다. 캐스케이드 도중 다른 기기가 계속 요청해 데이터를 다시
        // 만들거나, 같은 userId로 재가입한 사람이 옛 토큰으로 새 계정에 접근하는 것을 막는다.
        // 무효화가 실패하면(Redis 장애) 아무것도 지우지 않은 채 503으로 끝나 재시도할 수 있다.
        tokenRevocationStore.revokeAll(userId)
        // 개인정보보호법 제21조: 회원탈퇴 시 지체 없이 파기. Firestore 문서만 지우면
        // GCS 라이프사이클(gcs-lifecycle.json 상 최대 180일)까지 원본 영상이 남으므로
        // logId를 먼저 수집해 blob을 삭제한 뒤 Firestore를 지운다.
        // blob 삭제 실패는 계정 삭제를 막지 않는다(사용자가 "탈퇴가 안 된다" 상태에 갇히지 않도록) —
        // 개별 실패는 warn 로그로 남겨 사후 파기 재시도가 가능하도록 한다.
        val logIds = fallLogRepository.findLogIdsByUserId(userId)
        // GCS blob 삭제는 개별 GCS SDK 호출이라 서로 독립적 — 병렬로 처리해 blob 개수에 비례해
        // 탈퇴 응답이 느려지지 않게 한다. 개별 실패는 여전히 warn 로그만 남기고 탈퇴는 계속 진행.
        coroutineScope {
            logIds.map { logId ->
                async {
                    runCatching { storageService.deleteBlob("fall-videos/$logId.mp4") }
                        .onFailure { e ->
                            log.warn(
                                "fall-video blob 삭제 실패 — userId={}, logId={}, cause={}",
                                userId, logId, e.javaClass.simpleName
                            )
                        }
                }
            }.awaitAll()
        }

        // 아래 7개 삭제는 서로 다른 컬렉션에 대한 독립적인 Firestore 요청이라 병렬로 처리한다.
        // notifyElderAndGuardians가 피보호자 본인 + 보호자 각각에게 별도 알림 문서를 남기므로,
        // deleteByUserId(본인 알림)만으로는 부족해 방금 수집한 logIds로 보호자 인박스에 남은
        // 관련 알림 사본까지 deleteByLogIds로 함께 정리한다.
        // 위 blob 삭제와 동일하게 각 작업을 runCatching으로 격리한다 — 격리하지 않으면 하나가
        // 예외를 던질 때 coroutineScope가 나머지 형제 코루틴을 취소해(구조적 동시성) 일부
        // 컬렉션만 지워진 불확실한 상태로 남고, 맨 아래 계정 문서 삭제까지 막혀버린다.
        coroutineScope {
            val deletions = listOf(
                "fall_logs" to suspend { fallLogRepository.deleteByUserId(userId) },
                "realtime_data" to suspend { realtimeDataRepository.deleteByUserId(userId) },
                "login_history" to suspend { loginHistoryRepository.deleteByUserId(userId) },
                "settings" to suspend { settingsRepository.deleteByUserId(userId) },
                "notifications(본인)" to suspend { notificationRepository.deleteByUserId(userId) },
                "notifications(보호자 사본)" to suspend { notificationRepository.deleteByLogIds(logIds) },
                "guardian_links" to suspend { guardianLinkRepository.deleteAllInvolving(userId) },
                "consents" to suspend { consentRepository.deleteByUserId(userId) },
                // 사용자 문서 하위 서브컬렉션이라 계정 문서를 지워도 남는다 — 명시적으로 정리(B8).
                "fcm_tokens" to suspend { fcmTokenRepository.deleteAll(userId) },
                "user_emails" to suspend { userRepository.deleteEmailLookup(user.mail) },
                "user_phones" to suspend { userRepository.deletePhoneLookup(user.phone) }
            )
            deletions.map { (name, delete) ->
                async {
                    runCatching { delete() }
                        .onFailure { e ->
                            log.warn(
                                "탈퇴 시 연쇄 삭제 실패 — userId={}, collection={}, cause={}",
                                userId, name, e.javaClass.simpleName
                            )
                        }
                }
            }.awaitAll()
        }
        // 계정 문서 자체는 위 정리가 전부 끝난 뒤 마지막에 지운다.
        userRepository.deleteByUserId(userId)
    }
}
