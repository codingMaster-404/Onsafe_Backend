package com.onsafe.backend.domain.auth.model.dto

import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.databind.annotation.JsonNaming

// verifyResetCode 성공 시 발급되는 1회용 티켓(A3). reset-password 요청은 이 티켓을 첨부해야 통과된다 —
// 이전 `reset_verified:{userId}` 플래그 방식은 "이 userId가 인증을 마쳤다"는 사실만 남아,
// 인증한 사람과 재설정하는 사람이 같은지 확인하지 못했다(userId만 알면 10분 안에 누구나 변경 가능).
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class VerifyResetCodeResponse(
    val resetTicket: String
)
