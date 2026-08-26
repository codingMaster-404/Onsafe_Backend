package com.onsafe.backend.domain.auth.model.dto

import jakarta.validation.constraints.NotBlank

data class FindIdRequest(
    @field:NotBlank(message = "이름을 입력해주세요.")
    val name: String,

    @field:NotBlank(message = "이메일을 입력해주세요.")
    val mail: String
)
