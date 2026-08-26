package com.onsafe.backend.domain.user.model.dto

import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size

data class UserUpdateRequest(
    val name: String? = null,

    val currentPassword: String? = null,

    @field:Size(min = 8, max = 64, message = "비밀번호는 8자 이상 64자 이하여야 합니다.")
    @field:Pattern(
        regexp = "^(?=.*[A-Za-z])(?=.*\\d).+$",
        message = "비밀번호는 영문과 숫자를 모두 포함해야 합니다."
    )
    val password: String? = null,

    val mail: String? = null,

    @field:Pattern(regexp = "^01[016789]-?\\d{3,4}-?\\d{4}$", message = "전화번호 형식이 올바르지 않습니다.")
    val phone: String? = null,

    val address: String? = null,

    val addressDetail: String? = null
)
