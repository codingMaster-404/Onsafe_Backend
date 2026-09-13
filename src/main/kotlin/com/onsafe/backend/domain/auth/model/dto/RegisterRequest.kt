package com.onsafe.backend.domain.auth.model.dto

import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.databind.annotation.JsonNaming
import jakarta.validation.constraints.AssertTrue
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class RegisterRequest(
    @field:NotBlank(message = "아이디를 입력해주세요.")
    val userId: String,

    @field:Size(min = 8, max = 64, message = "비밀번호는 8자 이상 64자 이하여야 합니다.")
    // (?s)로 DOTALL 지정 — 없으면 "."이 개행(\n)과 매치되지 않아, 정상 비밀번호라도
    // 클립보드 붙여넣기·IME 이슈로 개행이 섞이면 ".+$"가 끝까지 못 가 거부된다.
    @field:Pattern(
        regexp = "(?s)^(?=.*[A-Za-z])(?=.*\\d)(?=.*[@\$!%*#?&]).+$",
        message = "비밀번호는 영문, 숫자, 특수문자(@\$!%*#?&)를 모두 포함해야 합니다."
    )
    val password: String,

    @field:NotBlank(message = "이름을 입력해주세요.")
    val name: String,

    @field:NotBlank(message = "이메일을 입력해주세요.")
    @field:Email(message = "이메일 형식이 올바르지 않습니다.")
    val mail: String,

    @field:Pattern(regexp = "^01[016789]-?\\d{3,4}-?\\d{4}$", message = "전화번호 형식이 올바르지 않습니다.")
    val phone: String,

    val address: String? = null,

    val addressDetail: String? = null,

    @field:AssertTrue(message = "이용약관에 동의해주세요.")
    val termsAgreed: Boolean,

    @field:AssertTrue(message = "개인정보 수집·이용에 동의해주세요.")
    val privacyPolicyAgreed: Boolean,

    @field:AssertTrue(message = "민감정보(건강·위치 데이터) 처리에 동의해주세요.")
    val sensitiveInfoAgreed: Boolean,

    val marketingConsent: Boolean = false,

    // verifyEmailCode 응답으로 받은 1회용 티켓. 이 요청과 verify 시점의 mail 소유자가
    // 동일한 사용자임을 서버가 확인하기 위한 값 — mail-단독 플래그만으로는 다른 사용자의
    // 인증 완료 상태를 자기 register에 도용해 이메일 선점이 가능해진다.
    @field:NotBlank(message = "이메일 인증 티켓이 필요합니다.")
    val emailVerifyTicket: String
)
