package com.onsafe.backend.domain.auth.model.dto

import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.databind.annotation.JsonNaming

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class LoginResponse(
    val userId: String,
    val deviceId: String,
    val name: String,
    val accessToken: String,
    val refreshToken: String,
    val tokenType: String = "Bearer"
)
