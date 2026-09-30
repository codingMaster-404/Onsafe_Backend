package com.onsafe.backend.domain.auth.model.dto

import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.databind.annotation.JsonNaming
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank

// 비밀번호 찾기 본인확인(A안). 메일 인증코드(SES)를 없앤 대신 세 값이 모두 맞아야 재설정 티켓을 준다.
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class VerifyResetIdentityRequest(
    @field:NotBlank(message = "아이디를 입력해주세요.")
    val userId: String,

    @field:NotBlank(message = "이름을 입력해주세요.")
    val name: String,

    @field:NotBlank(message = "이메일을 입력해주세요.")
    @field:Email(message = "이메일 형식이 올바르지 않습니다.")
    val mail: String
)
