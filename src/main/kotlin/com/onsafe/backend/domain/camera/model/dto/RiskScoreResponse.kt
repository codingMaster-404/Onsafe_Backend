package com.onsafe.backend.domain.camera.model.dto

import java.time.LocalDateTime

data class RiskScoreResponse(
    val userId: String,
    val score: Float,
    val level: String,
    val updatedAt: LocalDateTime
)
