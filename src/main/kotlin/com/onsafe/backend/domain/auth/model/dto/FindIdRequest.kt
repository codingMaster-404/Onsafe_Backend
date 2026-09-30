package com.onsafe.backend.domain.auth.model.dto

import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.databind.annotation.JsonNaming
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank

// 다른 auth DTO와 같이 snake_case로 받는다(name·mail은 표기가 같아 결과는 동일).
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class FindIdRequest(
    @field:NotBlank(message = "이름을 입력해주세요.")
    val name: String,

    @field:NotBlank(message = "이메일을 입력해주세요.")
    @field:Email(message = "이메일 형식이 올바르지 않습니다.")
    val mail: String
)
