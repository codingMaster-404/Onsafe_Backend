package com.onsafe.backend.domain.notification.model.dto

data class NotificationResponse(
    val status: String,
    val message: String,
    val fcmMessageId: String
)
