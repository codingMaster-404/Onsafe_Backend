package com.onsafe.backend.domain.logs.model.entity

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
)
