package com.onsafe.backend.domain.user.service

import com.onsafe.backend.common.exception.BusinessException
import com.onsafe.backend.common.exception.ErrorCode
import com.onsafe.backend.common.storage.StorageService
import com.onsafe.backend.domain.auth.repository.LoginHistoryRepository
import com.onsafe.backend.domain.camera.repository.RealtimeDataRepository
import com.onsafe.backend.domain.guardian.repository.GuardianLinkRepository
import com.onsafe.backend.domain.logs.repository.FallLogRepository
import com.onsafe.backend.domain.notification.repository.NotificationRepository
import com.onsafe.backend.domain.settings.repository.SettingsRepository
import com.onsafe.backend.domain.user.model.dto.UserResponse
import com.onsafe.backend.domain.user.model.dto.UserUpdateRequest
import com.onsafe.backend.domain.user.repository.UserRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.slf4j.LoggerFactory
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service

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
    private val guardianLinkRepository: GuardianLinkRepository
) {

    private val log = LoggerFactory.getLogger(javaClass)

    suspend fun getUser(userId: String): UserResponse {
        val user = userRepository.findByUserId(userId)
            ?: throw BusinessException(ErrorCode.USER_NOT_FOUND)
        return UserResponse.from(user)
    }

    suspend fun updateUser(userId: String, request: UserUpdateRequest): UserResponse {
        val user = userRepository.findByUserId(userId)
            ?: throw BusinessException(ErrorCode.USER_NOT_FOUND)
        if (request.password != null) {
            if (request.currentPassword == null || !passwordEncoder.matches(request.currentPassword, user.password)) {
                throw BusinessException(ErrorCode.INVALID_PASSWORD)
            }
        }
        val updated = user.copy(
            name = request.name ?: user.name,
            password = if (request.password != null) passwordEncoder.encode(request.password) else user.password,
            mail = request.mail ?: user.mail,
            phone = request.phone ?: user.phone,
            address = request.address ?: user.address,
            addressDetail = request.addressDetail ?: user.addressDetail
        )
        return UserResponse.from(userRepository.save(updated))
    }

    suspend fun verifyPassword(userId: String, currentPassword: String) {
        val user = userRepository.findByUserId(userId)
            ?: throw BusinessException(ErrorCode.USER_NOT_FOUND)
        if (!passwordEncoder.matches(currentPassword, user.password)) {
            throw BusinessException(ErrorCode.INVALID_PASSWORD)
        }
    }

    suspend fun deleteUser(userId: String) {
        if (!userRepository.existsByUserId(userId)) {
            throw BusinessException(ErrorCode.USER_NOT_FOUND)
        }
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
                "guardian_links" to suspend { guardianLinkRepository.deleteAllInvolving(userId) }
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
