package com.onsafe.backend.domain.camera.service

import com.onsafe.backend.common.exception.BusinessException
import com.onsafe.backend.common.exception.ErrorCode
import com.onsafe.backend.domain.camera.model.dto.RiskScoreResponse
import com.onsafe.backend.domain.camera.model.dto.RiskStatusResponse
import com.onsafe.backend.domain.camera.model.entity.RiskLevel
import com.onsafe.backend.domain.camera.repository.RealtimeDataRepository
import com.onsafe.backend.domain.notification.service.NotificationService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class CameraService(
    private val realtimeDataRepository: RealtimeDataRepository,
    private val notificationService: NotificationService,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    suspend fun getRiskScore(userId: String): RiskScoreResponse {
        val data = realtimeDataRepository.findByUserId(userId)
            ?: throw BusinessException(ErrorCode.REALTIME_DATA_NOT_FOUND)

        return RiskScoreResponse(
            userId = userId,
            score = data.score,
            level = data.level,
            updatedAt = data.updatedAt
        )
    }

    suspend fun getRiskStatus(userId: String): RiskStatusResponse {
        val data = realtimeDataRepository.findByUserId(userId)
            ?: throw BusinessException(ErrorCode.REALTIME_DATA_NOT_FOUND)
        val risk = RiskLevel.fromLabel(data.level)
        return RiskStatusResponse(
            userId = userId,
            level = risk.label,
            score = data.score,
            colorCode = risk.colorCode
        )
    }

    // Heartbeat 수신 진입점. userId 는 JWT principal 로 이미 검증된 값이라 그대로 활용.
    // 오프라인 알림을 받았던 상태(last_offline_notified_at != null)에서 heartbeat 이 다시 오면
    // 복구로 판정해 보호자에게 복구 알림을 발송한다. 재알림 게이트(last_offline_notified_at)는
    // upsertHeartbeat 이 null 로 리셋해 다음 오프라인 감지 시 다시 1회 알림이 나가게 한다.
    // 복구 알림 발송 실패는 runCatching 으로 격리 — heartbeat 자체 성공 응답을 막지 않는다.
    suspend fun recordHeartbeat(userId: String, powerSaveMode: Boolean) {
        val previous = realtimeDataRepository.upsertHeartbeat(userId = userId, powerSaveMode = powerSaveMode)
        val wasOfflineNotified = previous?.lastOfflineNotifiedAt != null
        if (wasOfflineNotified) {
            runCatching { notificationService.notifyGuardiansCameraRecovered(userId) }
                .onFailure { e -> log.warn("복구 알림 실패 — userId=$userId cause=${e.message}") }
        }
    }
}
