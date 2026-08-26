package com.onsafe.backend.domain.internal.model.dto

data class SaveFallLogRequest(
    val logId: String,
    val deviceId: String,
    val userId: String,
    val score: Float,
    val fall: Boolean,
    val isConfirmed: Boolean = false,
    val videoUrl: String? = null
)
