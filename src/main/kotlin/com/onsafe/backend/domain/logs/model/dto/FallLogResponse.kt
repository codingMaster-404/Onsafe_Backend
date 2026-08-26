package com.onsafe.backend.domain.logs.model.dto

import com.onsafe.backend.domain.logs.model.entity.FallLog
import java.time.LocalDateTime

data class FallLogResponse(
    val logId: String,
    val deviceId: String,
    val userId: String,
    val score: Float,
    val fall: Boolean,
    val isConfirmed: Boolean,
    val hasVideo: Boolean,
    val timestamp: LocalDateTime
) {
    companion object {
        fun from(log: FallLog) = FallLogResponse(
            logId = log.logId,
            deviceId = log.deviceId,
            userId = log.userId,
            score = log.score,
            fall = log.fall,
            isConfirmed = log.isConfirmed,
            hasVideo = log.videoUrl != null,
            timestamp = log.timestamp
        )
    }
}
