package com.onsafe.backend.domain.auth.model.dto

import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.databind.annotation.JsonNaming
import jakarta.validation.constraints.NotBlank

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class VerifyResetCodeRequest(
    @field:NotBlank(message = "아이디를 입력해주세요.")
    val userId: String,

    @field:NotBlank(message = "인증코드를 입력해주세요.")
    val code: String
)
