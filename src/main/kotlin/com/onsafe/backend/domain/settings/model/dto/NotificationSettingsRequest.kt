package com.onsafe.backend.domain.settings.model.dto

data class NotificationSettingsRequest(
    val notificationEnabled: Boolean? = null,
    val soundEnabled: Boolean? = null,
    val vibrationEnabled: Boolean? = null,
)
