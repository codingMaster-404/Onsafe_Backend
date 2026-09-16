package com.onsafe.backend.domain.auth.model.dto

import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.databind.annotation.JsonNaming
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class CheckIdRequest(
    @field:NotBlank(message = "아이디를 입력해주세요.")
    // 앱과 같은 규칙(영문·숫자 5~15자)을 서버에서도 강제한다 — 앞서는 검증이 앱에만 있어
    // API를 직접 호출하면 한 글자·특수문자·공백 아이디도 만들 수 있었다(프론트 PR #45). 가입과 규칙이 다르면 "사용 가능"으로 안내한 아이디가 가입에서 거부된다.
    @field:Pattern(
        regexp = "^[A-Za-z0-9]{5,15}$",
        message = "아이디는 영문·숫자 5~15자여야 합니다."
    )
    val userId: String
)
