package com.onsafe.backend.domain.consent.model.dto

import com.onsafe.backend.domain.consent.model.entity.ConsentType
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.NotNull

data class ConsentAgreeRequest(
    @field:NotEmpty(message = "동의할 약관을 선택해주세요.")
    @field:Valid
    val consents: List<ConsentAgreeItem>?
)

// 버전을 함께 받는 이유: 앱이 모달을 띄운 사이 약관이 또 개정됐다면 사용자는 옛 문구에 동의한 것이다.
// 서버가 현재 버전을 대신 채우면 보지 않은 약관에 동의한 기록이 남으므로, 불일치면 거부한다.
data class ConsentAgreeItem(
    @field:NotNull(message = "약관 종류가 필요합니다.")
    val type: ConsentType?,

    @field:NotBlank(message = "약관 버전이 필요합니다.")
    val version: String?
)
