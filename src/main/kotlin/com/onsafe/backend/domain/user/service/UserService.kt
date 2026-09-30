package com.onsafe.backend.domain.user.service

import com.onsafe.backend.common.exception.BusinessException
import com.onsafe.backend.common.exception.ErrorCode
import com.onsafe.backend.common.ratelimit.RateLimiter
import com.onsafe.backend.common.security.TokenRevocationStore
import com.onsafe.backend.common.storage.StorageService
import com.onsafe.backend.domain.auth.model.entity.LoginHistory
import com.onsafe.backend.domain.auth.model.entity.SecurityEventType
import com.onsafe.backend.domain.auth.repository.LoginHistoryRepository
import com.onsafe.backend.domain.camera.repository.RealtimeDataRepository
import com.onsafe.backend.domain.consent.repository.ConsentRepository
import com.onsafe.backend.domain.guardian.repository.GuardianLinkRepository
import com.onsafe.backend.domain.logs.repository.FallLogRepository
import com.onsafe.backend.domain.notification.repository.FcmTokenRepository
import com.onsafe.backend.domain.notification.repository.NotificationRepository
import com.onsafe.backend.domain.settings.repository.SettingsRepository
import com.onsafe.backend.common.util.guardRedis
import com.onsafe.backend.domain.user.model.entity.DeletionTask
import com.onsafe.backend.domain.user.model.dto.UserResponse
import com.onsafe.backend.domain.user.model.dto.VerifyPasswordResponse
import com.onsafe.backend.domain.user.model.dto.UserUpdateRequest
import com.onsafe.backend.domain.user.repository.DeletionTaskRepository
import com.onsafe.backend.domain.user.repository.UserRepository
import kotlinx.coroutines.CancellationException
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
    private val redis: ReactiveStringRedisTemplate,
    private val deletionTaskRepository: DeletionTaskRepository
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 보안 이벤트 기록(C6). IP·User-Agent는 채우지 않는다 — 이 경로들에는 `ServerWebExchange`가
     * 닿지 않고, 조사에서 결정적인 값은 로그인 시도의 IP다(완료 문서 D36).
     * 기록 실패가 요청을 막지 않도록 격리하되, 실패 사실은 error 로그로 남긴다.
     */
    private suspend fun recordSecurityEvent(userId: String, eventType: SecurityEventType) {
        runCatching {
            loginHistoryRepository.save(
                LoginHistory(historyId = "", userId = userId, eventType = eventType)
            )
        }.onFailure { e ->
            log.error("보안 이벤트 저장 실패 — userId={}, event={}, cause={}", userId, eventType, e.message, e)
        }
    }

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
     * 메일·전화는 값이 **실제로 바뀐 경우에만** 중복 검사를 한다. 앱이 바꾸지 않은 항목도 현재 값을
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

        // 메일 소유 확인은 하지 않는다(SES 제거) — 본인 확인(위 reauth)과 중복 검사(saveWithLookups)만 한다.
        // 실제로 바뀐 메일인지는 409 사유(메일/전화) 구분에 쓴다.
        val newMail = request.mail?.takeIf { !it.equals(user.mail, ignoreCase = true) }

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
        // 비밀번호 변경은 계정 탈취의 핵심 단계라 반드시 흔적을 남긴다(C6).
        if (changingPassword) recordSecurityEvent(userId, SecurityEventType.PASSWORD_CHANGE)
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

    /**
     * 회원 탈퇴(C5) — **완료 책임을 HTTP 연결에서 분리한다.**
     *
     * 탈퇴는 소요 시간이 데이터 양(영상 수·문서 수)에 비례해 가변인데, 이전에는 이를 동기 요청 안에서
     * 끝내려 해 클라이언트 연결이 작업의 생명줄이었다. 앱의 읽기 타임아웃(OkHttp 기본 10초)·앱 종료·
     * 네트워크 끊김·인스턴스 종료가 전부 "절반만 지워지고 계정은 남은" 상태로 수렴했다.
     *
     * 이제 순서는 이렇다.
     *  ① 재인증 티켓 확인(B6)  ② 전 기기 세션 무효화
     *  ③ **파기 작업 기록 + 계정 문서 삭제를 한 트랜잭션으로**(여기까지 밀리초)
     *  ④ 나머지 정리는 best-effort — 중단되면 `deletion_tasks`를 보고 잡이 마무리한다
     *
     * ③ 이전에 실패하면 **아무것도 지워지지 않아** 재시도가 안전하고, ③ 이후에는 **반드시 완료된다.**
     */
    suspend fun deleteUser(userId: String, reauthTicket: String?) {
        // 룩업 키(mail·phone)와 blob 경로(logIds)는 계정 문서가 사라지면 알 수 없다 — 미리 확보해 task에 싣는다.
        val user = userRepository.findByUserId(userId)
            ?: throw BusinessException(ErrorCode.USER_NOT_FOUND)

        // 되돌릴 수 없는 작업이라 본인 확인을 요구한다(B6). 티켓은 verify-password가 발급한다(D25).
        if (!isReauthVerified(reauthTicket, userId)) throw BusinessException(ErrorCode.REAUTH_REQUIRED)

        // 세션부터 끊는다. 실패하면(Redis 장애) 아무것도 지우지 않은 채 503으로 끝나 재시도할 수 있다.
        tokenRevocationStore.revokeAll(userId)

        val logIds = fallLogRepository.findLogIdsByUserId(userId)
        val task = DeletionTask(userId = userId, mail = user.mail, phone = user.phone, logIds = logIds)

        // 계정 문서가 사라지기 **전에** 남긴다 — 이력은 별도 컬렉션이라 탈퇴 후에도 조사에 쓸 수 있다(C6).
        recordSecurityEvent(userId, SecurityEventType.ACCOUNT_DELETED)

        // ③ 임계 구간 — 이 트랜잭션이 끝나면 사용자 관점에서 탈퇴는 완료다(계정 소멸).
        userRepository.createDeletionTaskAndDeleteUser(task)
        deleteReauthTicket(reauthTicket)

        // ④ 여기서부터는 중단돼도 무방하다 — task가 남아 있으므로 잡이 이어서 끝낸다.
        try {
            purge(task)
            deletionTaskRepository.delete(userId)
        } catch (e: CancellationException) {
            // 클라이언트가 끊겼거나 인스턴스가 종료되는 중이다. task가 남아 잡이 마무리한다.
            log.info("탈퇴 정리 중단(잡이 이어서 처리) — userId={}", userId)
            throw e
        } catch (e: Exception) {
            log.warn("탈퇴 정리 일부 실패(잡이 재시도) — userId={}, cause={}", userId, e.javaClass.simpleName)
        }
    }

    /**
     * 파기 본체 — **멱등**하다. 요청 경로와 재시도 잡이 같은 코드를 쓴다.
     * 이미 지워진 대상을 다시 지워도 문제가 없어야 몇 번을 재실행하든 결과가 같다.
     *
     * 개인정보보호법 제21조: 회원탈퇴 시 지체 없이 파기. Firestore 문서만 지우면 GCS 라이프사이클
     * (최대 180일)까지 원본 영상이 남으므로 blob도 함께 지운다.
     */
    suspend fun purge(task: DeletionTask) {
        val userId = task.userId
        // GCS blob 삭제는 개별 SDK 호출이라 서로 독립적 — 병렬로 처리한다.
        coroutineScope {
            task.logIds.map { logId ->
                async { guarded("fall-video:$logId", userId) { storageService.deleteBlob("fall-videos/$logId.mp4") } }
            }.awaitAll()
        }

        // 서로 다른 컬렉션에 대한 독립적인 요청이라 병렬로 처리한다.
        // notifyElderAndGuardians가 피보호자 본인 + 보호자 각각에게 알림 문서를 남기므로,
        // 본인 알림(deleteByUserId)만으로는 부족해 logIds로 보호자 인박스 사본까지 정리한다.
        coroutineScope {
            val deletions = listOf(
                "fall_logs" to suspend { fallLogRepository.deleteByUserId(userId) },
                "realtime_data" to suspend { realtimeDataRepository.deleteByUserId(userId) },
                "login_history" to suspend { loginHistoryRepository.deleteByUserId(userId) },
                "settings" to suspend { settingsRepository.deleteByUserId(userId) },
                "notifications(본인)" to suspend { notificationRepository.deleteByUserId(userId) },
                "notifications(보호자 사본)" to suspend { notificationRepository.deleteByLogIds(task.logIds) },
                "guardian_links" to suspend { guardianLinkRepository.deleteAllInvolving(userId) },
                "consents" to suspend { consentRepository.deleteByUserId(userId) },
                // 사용자 문서 하위 서브컬렉션이라 계정 문서를 지워도 남는다 — 명시적으로 정리(B8).
                "fcm_tokens" to suspend { fcmTokenRepository.deleteAll(userId) },
                "user_emails" to suspend { userRepository.deleteEmailLookup(task.mail) },
                "user_phones" to suspend { userRepository.deletePhoneLookup(task.phone) },
                // 탈퇴해도 TTL 만료 전까지 남는 Redis 키 정리(D6).
                "redis_keys" to suspend { deleteRedisLeftovers(userId) },
            )
            deletions.map { (name, delete) ->
                async { guarded(name, userId) { delete() } }
            }.awaitAll()
        }
    }

    /**
     * 대상별 실패를 격리하되 **취소는 반드시 다시 던진다**(`guardRedis`와 같은 규칙).
     * 취소까지 삼키면 ① 이미 끝난 삭제가 "실패"로 기록되고 ② 구조적 동시성 때문에 이후 단계가
     * 실행되지 않은 채 조용히 끝난다 — 이 함수가 그 두 가지를 모두 막는다.
     */
    private suspend fun guarded(target: String, userId: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("탈퇴 파기 실패 — userId={}, target={}, cause={}", userId, target, e.javaClass.simpleName)
            throw e // 상위(purge 호출부)가 task를 남겨 잡이 재시도하게 한다
        }
    }

    /** 탈퇴 후 TTL 만료 전까지 남는 Redis 키 정리(D6). 페어링 코드는 owner 키로 역추적해 함께 지운다. */
    private suspend fun deleteRedisLeftovers(userId: String) {
        log.guardRedis("탈퇴 Redis 키 정리") {
            // 무효화 키(token_valid_after:{userId})는 **지우지 않는다** — 지우면 탈퇴 전에 발급된
            // 토큰이 다시 통과한다. TTL(30일)로 자연 소멸하게 둔다.
            val code = redis.opsForValue().getAndDelete("pairing_code_owner:$userId").awaitFirstOrNull()
            // 지울 키가 없으면 DEL을 보내지 않는다 — 인자 없는 DEL은 Redis가 오류로 거부한다.
            if (code != null) redis.delete("pairing_code:$code").awaitSingle()
        }
    }
}
