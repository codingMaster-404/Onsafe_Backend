package com.onsafe.backend.domain.logs.model.entity

import java.time.Duration
import java.time.LocalDateTime

data class FallLog(
    val logId: String,
    val deviceId: String,
    val userId: String,
    val score: Float,
    val fall: Boolean,
    val isConfirmed: Boolean = false,
    val videoUrl: String? = null,
    val lastReminderAt: LocalDateTime? = null,  // 미확인 위험 이벤트 에스컬레이션 리마인더 마지막 발송 시각
    val timestamp: LocalDateTime = LocalDateTime.now()
) {
    // Firestore TTL 정책 대상 필드(expired_at) 값. 영상은 GCS lifecycle이 같은 기간으로 지운다(gcs-lifecycle.json).
    val expiredAt: LocalDateTime get() = timestamp.plus(RETENTION_PERIOD)

    companion object {
        // 앱 안내 "사고 이력은 발생일로부터 최대 30일간 보관"과 맞춘 값
        val RETENTION_PERIOD: Duration = Duration.ofDays(30)
    }
}
