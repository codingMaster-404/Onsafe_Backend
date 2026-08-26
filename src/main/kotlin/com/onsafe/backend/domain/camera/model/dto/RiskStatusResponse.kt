package com.onsafe.backend.domain.camera.model.dto

data class RiskStatusResponse(
    val userId: String,
    val level: String,
    val score: Float,
    val colorCode: String
)
