package com.onsafe.backend.domain.notification.model.entity

import java.time.Duration
import java.time.LocalDateTime

data class Notification(
    val notificationId: String = "",
    val userId: String,
    val title: String,
    val body: String,
    // FallLogResponse와 동일한 필드 구성 — 클라이언트가 score > RiskLevel.DANGER_THRESHOLD로
    // FALL/WARNING 타입을 직접 판별하는 기존 AccidentHistory 패턴을 그대로 재사용할 수 있게 한다.
    val logId: String? = null,
    val score: Float? = null,
    val fall: Boolean = false,
    val isRead: Boolean = false,
    val createdAt: LocalDateTime = LocalDateTime.now()
) {
    // Firestore TTL 정책 대상 필드(expired_at) 값
    val expiredAt: LocalDateTime get() = createdAt.plus(RETENTION_PERIOD)

    companion object {
        // 앱 안내 "알림 내역은 최근 7일간의 내역만 보관"과 맞춘 값
        val RETENTION_PERIOD: Duration = Duration.ofDays(7)
    }
}
