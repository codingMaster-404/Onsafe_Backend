package com.onsafe.backend.domain.notification.model.dto

import com.onsafe.backend.domain.notification.model.entity.Notification
import java.time.LocalDateTime

data class NotificationLogResponse(
    val notificationId: String,
    val title: String,
    val body: String,
    val logId: String?,
    val score: Float?,
    val fall: Boolean,
    val isRead: Boolean,
    val timestamp: LocalDateTime
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
            timestamp = notification.createdAt
        )
    }
}
