package com.onsafe.backend.domain.logs.service

import com.onsafe.backend.common.exception.BusinessException
import com.onsafe.backend.common.exception.ErrorCode
import com.onsafe.backend.common.storage.StorageService
import com.onsafe.backend.domain.auth.model.entity.LoginHistory
import com.onsafe.backend.domain.auth.model.entity.SecurityEventType
import com.onsafe.backend.domain.auth.repository.LoginHistoryRepository
import com.onsafe.backend.domain.camera.model.entity.RiskLevel
import com.onsafe.backend.domain.logs.model.dto.FallLogResponse
import com.onsafe.backend.domain.logs.model.entity.FallLog
import com.onsafe.backend.domain.logs.repository.FallLogRepository
import com.onsafe.backend.domain.notification.repository.NotificationRepository
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.LocalDateTime

@Service
class FallLogService(
    private val fallLogRepository: FallLogRepository,
    private val storageService: StorageService,
    private val notificationRepository: NotificationRepository,
    private val loginHistoryRepository: LoginHistoryRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // since 는 AccessGuard.Grant.Guardian.since (연결 시각) — 보호자 호출 시 이 값을 넘겨
    // 이 이전에 발생한 이력이 새 보호자에게 노출되지 않게 한다. 본인 호출은 null.
    suspend fun getLogs(userId: String, level: String? = null, since: LocalDateTime? = null): List<FallLogResponse> =
        fallLogRepository.findRecentByUserId(userId, level, since).map { FallLogResponse.from(it) }

    suspend fun getLogCounts(userId: String, since: LocalDateTime? = null): Map<String, Int> =
        fallLogRepository.countByUserId(userId, since)

    suspend fun getLog(userId: String, logId: String, since: LocalDateTime? = null): FallLogResponse {
        val log = fallLogRepository.findByLogIdAndUserId(logId, userId, since)
            ?: throw BusinessException(ErrorCode.LOG_NOT_FOUND)
        return FallLogResponse.from(log)
    }

    suspend fun confirmLog(userId: String, logId: String, since: LocalDateTime? = null): FallLogResponse {
        val log = fallLogRepository.confirmByLogIdAndUserId(logId, userId, since)
            ?: throw BusinessException(ErrorCode.LOG_NOT_FOUND)
        return FallLogResponse.from(log)
    }

    /**
     * 사고 이력 삭제 — 본인 또는 연결된 보호자([deletedBy] != [userId]면 보호자, [since] 이후 이력만).
     *
     * 보호자는 확인하지 않은 위험 이벤트를 지울 수 없다 — 지우면 미확인 재알림이 멈춘다. 본인은 앱에 삭제 경로가 없어 막지 않는다.
     * 문서를 지운 뒤 영상(`fall-videos/{logId}.mp4`)과 알림 사본(본인·보호자 인박스)도 지운다 — 문서가 없으면 둘 다
     * 쓸 수 없고(signed URL 불가·알림 누르면 404) 영상은 버킷 lifecycle까지 남는다. 정리는 best-effort(탈퇴 정리와 같은 방식).
     */
    suspend fun deleteLog(userId: String, logId: String, deletedBy: String = userId, since: LocalDateTime? = null) {
        val target = fallLogRepository.findByLogIdAndUserId(logId, userId, since)
            ?: throw BusinessException(ErrorCode.LOG_NOT_FOUND)
        val byGuardian = deletedBy != userId
        if (byGuardian && target.isDangerLevel() && !target.isConfirmed) {
            throw BusinessException(ErrorCode.LOG_NOT_CONFIRMED)
        }

        fallLogRepository.deleteByLogId(logId)

        cleanUp("fall-video:$logId", logId) { storageService.deleteBlob("fall-videos/$logId.mp4") }
        cleanUp("notifications", logId) { notificationRepository.deleteByLogIds(listOf(logId)) }
        // 다른 사람(보호자)이 피보호자 기록을 지운 흔적 — "누가 내 기록을 지웠나"를 남긴다.
        if (byGuardian) {
            cleanUp("security-event", logId) {
                loginHistoryRepository.save(
                    LoginHistory(historyId = "", userId = deletedBy, eventType = SecurityEventType.FALL_LOG_DELETED, targetUserId = userId)
                )
            }
        }
    }

    // 삭제 뒤 정리 실패가 삭제 응답을 막지 않게 격리한다. 취소는 다시 던진다(UserService.guarded와 같은 규칙).
    private suspend fun cleanUp(target: String, logId: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("사고 이력 삭제 후 정리 실패 — target={}, logId={}: {}", target, logId, e.message)
        }
    }

    suspend fun getSignedUrl(userId: String, logId: String, since: LocalDateTime? = null): String {
        val log = fallLogRepository.findByLogIdAndUserId(logId, userId, since)
            ?: throw BusinessException(ErrorCode.LOG_NOT_FOUND)
        val url = log.videoUrl ?: throw BusinessException(ErrorCode.VIDEO_NOT_FOUND)
        return storageService.generateSignedUrl(url)
    }

    /** Android가 GCS에 낙상 동영상을 직접 업로드할 수 있도록 signed PUT URL을 발급한다 (#14).
     * 영상은 위험(경고) 등급에만 제공한다는 정책(8절) — 주의 등급 로그는 거부. */
    suspend fun getUploadUrl(userId: String, logId: String): String {
        val log = fallLogRepository.findByLogIdAndUserId(logId, userId)
            ?: throw BusinessException(ErrorCode.LOG_NOT_FOUND)
        if (!log.isDangerLevel()) throw BusinessException(ErrorCode.VIDEO_NOT_ALLOWED)
        return storageService.generateSignedUploadUrl("fall-videos/$logId.mp4")
    }

    /**
     * Android가 GCS 업로드 완료 후 호출하는 콜백 (#15). 클라이언트 주장만으로 video_url을 채우지 않고,
     * GCS에 실제 객체가 존재하는지 확인한 뒤에만 Firestore를 갱신한다. 여기서도 위험 등급만 허용(#14와 동일 정책).
     */
    suspend fun completeVideoUpload(userId: String, logId: String): FallLogResponse {
        val existing = fallLogRepository.findByLogIdAndUserId(logId, userId)
            ?: throw BusinessException(ErrorCode.LOG_NOT_FOUND)
        if (!existing.isDangerLevel()) throw BusinessException(ErrorCode.VIDEO_NOT_ALLOWED)

        val gcsPath = "fall-videos/$logId.mp4"
        if (!storageService.blobExists(gcsPath)) throw BusinessException(ErrorCode.VIDEO_NOT_FOUND)

        val log = fallLogRepository.setVideoUrlByLogIdAndUserId(logId, userId, gcsPath)
            ?: throw BusinessException(ErrorCode.LOG_NOT_FOUND)
        return FallLogResponse.from(log)
    }

    private fun FallLog.isDangerLevel(): Boolean = score > RiskLevel.DANGER_THRESHOLD || fall
}
