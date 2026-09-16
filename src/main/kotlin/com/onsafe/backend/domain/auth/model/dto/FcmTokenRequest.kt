package com.onsafe.backend.domain.auth.model.dto

import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.databind.annotation.JsonNaming
import jakarta.validation.constraints.NotBlank

// FCM 토큰 등록(POST)·해제(DELETE) 공통 요청. 해제도 토큰을 함께 받아야 "어느 기기의 어느 토큰"을
// 지울지 서버가 알 수 있다 — deviceId만 받으면 그 사이 다른 기기가 갱신한 최신 토큰을 지울 수 있다.
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class FcmTokenRequest(
    @field:NotBlank(message = "FCM 토큰이 필요합니다.")
    val fcmToken: String,

    @field:NotBlank(message = "기기 ID가 필요합니다.")
    val deviceId: String
)
