package com.onsafe.backend.domain.auth.model.dto

import jakarta.validation.constraints.NotBlank

data class FcmTokenUpdateRequest(
    @field:NotBlank(message = "FCM 토큰을 입력해주세요.")
    val fcmToken: String
)
