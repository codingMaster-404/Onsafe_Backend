package com.onsafe.backend.domain.auth.model.dto

import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.databind.annotation.JsonNaming

// verifyEmailCode 성공 시 발급되는 1회용 티켓. 이후 register 요청은 이 티켓을 첨부해야 통과된다 —
// 다른 사용자가 인증만 완료된 상태(예: A가 verify 후 아직 register 안 함)에서 A의 이메일 주소를
// 도용해 자기 계정으로 붙이는 선점(squatting) 시나리오를 원천 차단한다.
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class VerifyEmailCodeResponse(
    val emailVerifyTicket: String
)