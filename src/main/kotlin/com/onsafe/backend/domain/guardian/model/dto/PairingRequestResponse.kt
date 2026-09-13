package com.onsafe.backend.domain.guardian.model.dto

import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.databind.annotation.JsonNaming

// pair() 진입점의 새 응답. 기존 즉시 성립 방식과 달리 피보호자 승인 대기 상태로 시작하므로,
// 실제 elder 정보 대신 대기 중인 요청 식별자와 남은 만료 시간만 돌려준다. 보호자 앱은 이 응답
// 이후 피보호자가 승인/거부하기 전까지는 상대 정보를 알 수 없다 — 승인 결과는 별도 FCM(event =
// pairing_approved / pairing_rejected)으로 도착한다.
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class PairingRequestResponse(
    val requestId: String,
    val expiresInSeconds: Long,
)