package com.onsafe.backend.domain.logs.service

import com.onsafe.backend.common.exception.BusinessException
import com.onsafe.backend.common.exception.ErrorCode
import com.onsafe.backend.common.storage.StorageService
import com.onsafe.backend.domain.camera.model.entity.RiskLevel
import com.onsafe.backend.domain.logs.model.dto.FallLogResponse
import com.onsafe.backend.domain.logs.model.entity.FallLog
import com.onsafe.backend.domain.logs.repository.FallLogRepository
import org.springframework.stereotype.Service

@Service
class FallLogService(
    private val fallLogRepository: FallLogRepository,
    private val storageService: StorageService
) {

    suspend fun getLogs(userId: String, level: String? = null): List<FallLogResponse> =
        fallLogRepository.findRecentByUserId(userId, level).map { FallLogResponse.from(it) }

    suspend fun getLogCounts(userId: String): Map<String, Int> =
        fallLogRepository.countByUserId(userId)

    suspend fun getLog(userId: String, logId: String): FallLogResponse {
        val log = fallLogRepository.findByLogIdAndUserId(logId, userId)
            ?: throw BusinessException(ErrorCode.LOG_NOT_FOUND)
        return FallLogResponse.from(log)
    }

    suspend fun confirmLog(userId: String, logId: String): FallLogResponse {
        val log = fallLogRepository.confirmByLogIdAndUserId(logId, userId)
            ?: throw BusinessException(ErrorCode.LOG_NOT_FOUND)
        return FallLogResponse.from(log)
    }

    suspend fun deleteLog(userId: String, logId: String) {
        val deleted = fallLogRepository.deleteByLogIdAndUserId(logId, userId)
        if (deleted == 0L) throw BusinessException(ErrorCode.LOG_NOT_FOUND)
    }

    suspend fun getSignedUrl(userId: String, logId: String): String {
        val log = fallLogRepository.findByLogIdAndUserId(logId, userId)
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
