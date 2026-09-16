package com.onsafe.backend.domain.user.model.dto

import jakarta.validation.constraints.NotBlank

data class VerifyPasswordRequest(
    @field:NotBlank(message = "비밀번호를 입력해주세요.")
    val currentPassword: String
)
