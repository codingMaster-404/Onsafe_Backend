package com.onsafe.backend.domain.user.model.dto

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size

data class UserUpdateRequest(
    // null이면 "변경 안 함"이라 @NotBlank를 쓸 수 없다(null을 거부해버린다). 대신 값이 온 경우에만
    // 길이와 공백을 검사한다 — 앱은 빈 이름을 막지만 API를 직접 호출하면 ""가 그대로 저장돼
    // 낙상 알림이 "[] 낙상이 감지되었습니다"로 나간다.
    @field:Size(min = 1, max = 30, message = "이름은 1자 이상 30자 이하여야 합니다.")
    @field:Pattern(regexp = "^(?!\\s*\$).+\$", message = "이름을 입력해주세요.")
    val name: String? = null,

    val currentPassword: String? = null,

    @field:Size(min = 8, max = 64, message = "비밀번호는 8자 이상 64자 이하여야 합니다.")
    // (?s)로 DOTALL 지정 — 없으면 "."이 개행(\n)과 매치되지 않아, 정상 비밀번호라도
    // 클립보드 붙여넣기·IME 이슈로 개행이 섞이면 ".+$"가 끝까지 못 가 거부된다.
    @field:Pattern(
        regexp = "(?s)^(?=.*[A-Za-z])(?=.*\\d)(?=.*[@\$!%*#?&]).+$",
        message = "비밀번호는 영문, 숫자, 특수문자(@\$!%*#?&)를 모두 포함해야 합니다."
    )
    val password: String? = null,

    @field:Email(message = "이메일 형식이 올바르지 않습니다.")
    val mail: String? = null,

    @field:Pattern(regexp = "^01[016789]-?\\d{3,4}-?\\d{4}$", message = "전화번호 형식이 올바르지 않습니다.")
    val phone: String? = null,

    val address: String? = null,

    val addressDetail: String? = null,

    // verify-password 응답으로 받은 재인증 티켓(B1). 비밀번호를 바꿀 때는 current_password로
    // 대신할 수 있지만, 그 외 항목(이름·메일·전화·주소) 변경에는 이 티켓이 필요하다.
    val reauthTicket: String? = null,

    // 메일을 **실제로 바꿀 때만** 필요한 이메일 인증 티켓(verify-email-code 응답).
    // 앱은 메일을 바꾸지 않아도 현재 값을 함께 보내므로, 값이 달라진 경우에만 요구한다.
    val emailVerifyTicket: String? = null
)
