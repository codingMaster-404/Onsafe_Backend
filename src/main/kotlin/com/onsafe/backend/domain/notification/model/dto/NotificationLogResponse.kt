package com.onsafe.backend.domain.notification.model.dto

import com.onsafe.backend.domain.notification.model.entity.Notification
import java.time.LocalDateTime

/**
 * 알림함 항목. [event]는 알림 종류(푸시 data의 event와 같은 코드), [elderUserId]는 이 알림의 대상 피보호자 —
 * 보호자 알림이면 그 피보호자, 피보호자 본인 낙상 알림이면 본인, 대상이 없는 페어링 요청·거절·해제·교체 통지는 null.
 * 두 필드 모두 도입 전 문서는 null일 수 있다(event는 v4.22부터 저장, 대상은 v4.18부터).
 */
data class NotificationLogResponse(
    val notificationId: String,
    val title: String,
    val body: String,
    val logId: String?,
    val score: Float?,
    val fall: Boolean,
    val isRead: Boolean,
    val timestamp: LocalDateTime,
    val event: String?,
    val elderUserId: String?,
) {
    companion object {
        fun from(notification: Notification) = NotificationLogResponse(
            notificationId = notification.notificationId,
            title = notification.title,
            body = notification.body,
            logId = notification.logId,
            score = notification.score,
            fall = notification.fall,
            isRead = notification.isRead,
            timestamp = notification.createdAt,
            event = notification.event,
            elderUserId = notification.subjectUserId,
        )
    }
}
