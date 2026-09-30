package com.onsafe.backend.domain.auth.model.dto

import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.databind.annotation.JsonNaming

// verifyResetIdentity 성공 시 발급되는 1회용 티켓. reset-password 요청은 이 티켓을 첨부해야 통과된다 —
// 서버가 GETDEL로 소비하면서 저장된 userId와 요청 userId를 대조한다.
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class VerifyResetIdentityResponse(
    val resetTicket: String
)
