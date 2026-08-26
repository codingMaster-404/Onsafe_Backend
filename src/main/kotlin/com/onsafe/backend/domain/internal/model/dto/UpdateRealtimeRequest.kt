package com.onsafe.backend.domain.internal.model.dto

data class UpdateRealtimeRequest(
    val userId: String,
    val score: Float,
    val level: String
)
