package com.onsafe.backend.domain.camera.service

import com.onsafe.backend.common.exception.BusinessException
import com.onsafe.backend.common.exception.ErrorCode
import com.onsafe.backend.domain.camera.model.dto.RiskScoreResponse
import com.onsafe.backend.domain.camera.model.dto.RiskStatusResponse
import com.onsafe.backend.domain.camera.model.entity.RiskLevel
import com.onsafe.backend.domain.camera.repository.RealtimeDataRepository
import org.springframework.stereotype.Service

@Service
class CameraService(
    private val realtimeDataRepository: RealtimeDataRepository
) {

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
}
