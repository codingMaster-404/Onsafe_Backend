package com.onsafe.backend.domain.auth.model.dto

import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.databind.annotation.JsonNaming
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank

// 이 DTO만 @JsonNaming이 빠져 있었다. name·mail은 표기가 같아 티가 안 났지만,
// email_verify_ticket부터는 다른 API와 같은 snake_case로 받아야 한다.
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class FindIdRequest(
    @field:NotBlank(message = "이름을 입력해주세요.")
    val name: String,

    @field:NotBlank(message = "이메일을 입력해주세요.")
    @field:Email(message = "이메일 형식이 올바르지 않습니다.")
    val mail: String,

    // verifyEmailCode 응답으로 받은 1회용 티켓(C1). 가입과 같은 키를 쓴다 —
    // "이 메일의 소유자"라는 뜻이라 용도 구분이 필요 없다.
    @field:NotBlank(message = "이메일 인증이 필요합니다. 인증코드 확인부터 다시 진행해주세요.")
    val emailVerifyTicket: String
)
