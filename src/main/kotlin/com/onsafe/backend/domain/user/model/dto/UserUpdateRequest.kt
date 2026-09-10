package com.onsafe.backend.domain.user.model.dto

import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size

data class UserUpdateRequest(
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

    val mail: String? = null,

    @field:Pattern(regexp = "^01[016789]-?\\d{3,4}-?\\d{4}$", message = "전화번호 형식이 올바르지 않습니다.")
    val phone: String? = null,

    val address: String? = null,

    val addressDetail: String? = null
)
