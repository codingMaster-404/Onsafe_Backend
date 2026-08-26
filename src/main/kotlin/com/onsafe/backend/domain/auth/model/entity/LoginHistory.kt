package com.onsafe.backend.domain.auth.model.entity

import java.time.LocalDateTime

data class LoginHistory(
    val historyId: String,
    val userId: String,
    val ipAddress: String,
    val userAgent: String,
    val success: Boolean,
    val failReason: String? = null,
    val timestamp: LocalDateTime = LocalDateTime.now()
)