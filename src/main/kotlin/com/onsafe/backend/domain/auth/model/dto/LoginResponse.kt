package com.onsafe.backend.domain.auth.model.dto

import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.databind.annotation.JsonNaming
import com.onsafe.backend.domain.consent.model.dto.PendingConsentResponse

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class LoginResponse(
    val userId: String,
    val deviceId: String,
    val name: String,
    val accessToken: String,
    val refreshToken: String,
    val tokenType: String = "Bearer",
    // 현재 시행 버전에 아직 동의하지 않은 필수 약관(A3). 비어 있지 않으면 앱이 재동의 모달을 띄운다.
    val pendingConsents: List<PendingConsentResponse> = emptyList()
)
