package com.onsafe.backend.domain.internal.service

import com.onsafe.backend.domain.camera.model.entity.RealtimeData
import com.onsafe.backend.domain.camera.model.entity.RiskLevel
import com.onsafe.backend.domain.camera.repository.RealtimeDataRepository
import com.onsafe.backend.domain.guardian.repository.GuardianLinkRepository
import com.onsafe.backend.domain.internal.model.dto.SaveFallLogRequest
import com.onsafe.backend.domain.internal.model.dto.UpdateRealtimeRequest
import com.onsafe.backend.domain.logs.model.entity.FallLog
import com.onsafe.backend.domain.logs.repository.FallLogRepository
import com.onsafe.backend.domain.notification.service.NotificationService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class InternalService(
    private val realtimeDataRepository: RealtimeDataRepository,
    private val fallLogRepository: FallLogRepository,
    private val notificationService: NotificationService,
    private val guardianLinkRepository: GuardianLinkRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // 카메라 모드는 페어링된 상태에서만 의미가 있다 — 보호자 없이 홀로 저장·감지해도 알림받을
    // 대상이 없으니 스토리지·처리 비용만 소비. 이 guard-rail 은 프론트가 카메라 모드 진입을
    // 페어링 여부로 통제하는 1차 방어에 이어, 앱 우회·버그 상황을 대비한 2차 방어다.
    // 회의록 §1a·1b(감시받지 않는 카메라)에 대한 대응: 폴백 발송이 아니라 저장 자체를 스킵.
    private suspend fun hasGuardian(userId: String): Boolean =
        guardianLinkRepository.existsByElder(userId)

    suspend fun updateRealtime(req: UpdateRealtimeRequest) {
        if (!hasGuardian(req.userId)) {
            log.debug("guardian 없음 — realtime 저장 스킵 (userId={})", req.userId)
            return
        }
        val existing = realtimeDataRepository.findByUserId(req.userId)
        val data = existing?.copy(score = req.score, level = req.level)
            ?: RealtimeData(userId = req.userId, score = req.score, level = req.level)
        realtimeDataRepository.save(data)
    }

    suspend fun saveFallLog(req: SaveFallLogRequest) {
        if (!hasGuardian(req.userId)) {
            log.info("guardian 없음 — fall log 저장/알림 스킵 (userId={}, logId={})", req.userId, req.logId)
            return
        }
        fallLogRepository.save(
            FallLog(
                logId = req.logId,
                deviceId = req.deviceId,
                userId = req.userId,
                score = req.score,
                fall = req.fall,
                isConfirmed = req.isConfirmed,
                videoUrl = req.videoUrl
            )
        )
        val notifData = mapOf("log_id" to req.logId, "user_id" to req.userId, "score" to req.score.toString())
        if (req.fall || req.score > RiskLevel.DANGER_THRESHOLD) {
            notifySafe(
                userId = req.userId,
                title = if (req.fall) "낙상 감지 경보" else "위험 수준 감지",
                body = if (req.fall) "낙상이 감지되었습니다. 즉시 확인하세요." else "위험 수준의 움직임이 감지되었습니다. 확인하세요.",
                logId = req.logId,
                score = req.score,
                fall = req.fall,
                data = notifData
            )
        } else if (req.score > RiskLevel.WARNING_THRESHOLD) {
            notifySafe(
                userId = req.userId,
                title = "주의 수준 감지",
                body = "주의가 필요한 움직임이 감지되었습니다. 확인하세요.",
                logId = req.logId,
                score = req.score,
                fall = false,
                data = notifData
            )
        }
    }

    // FCM 실패는 이력 저장과 독립적으로 처리 — 전송 실패해도 DB 저장은 항상 보장.
    // 피보호자 본인 + 연결된 보호자 전원에게 발송(notifyElderAndGuardians)한다.
    private suspend fun notifySafe(
        userId: String,
        title: String,
        body: String,
        logId: String,
        score: Float,
        fall: Boolean,
        data: Map<String, String>
    ) {
        runCatching {
            notificationService.notifyElderAndGuardians(
                elderUserId = userId, title = title, body = body, logId = logId, score = score, fall = fall, data = data
            )
        }.onFailure { e -> log.error("알림 전송 실패 (userId: $userId): ${e.message}", e) }
    }
}
