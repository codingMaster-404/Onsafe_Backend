package com.onsafe.backend.domain.camera.model.entity

import java.time.LocalDateTime

data class RealtimeData(
    val userId: String,
    val score: Float = 0f,
    val level: String = "정상",
    val updatedAt: LocalDateTime = LocalDateTime.now()
)
