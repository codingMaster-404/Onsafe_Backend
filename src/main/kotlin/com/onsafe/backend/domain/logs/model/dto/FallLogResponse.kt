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
    // 영상 시작부터 위험 점수가 가장 높았던 시점(ms) — 보호자 앱이 이 위치 프레임을 썸네일로 쓴다. 이전 기록은 null
    val peakOffsetMs: Long?,
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
            peakOffsetMs = log.peakOffsetMs,
            timestamp = log.timestamp
        )
    }
}
