package com.onsafe.backend.domain.guardian.model.dto

import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.databind.annotation.JsonNaming

// 해제 전 확인(S4) 응답. 앱은 1건 이상이면 "확인되지 않은 위험 알림이 n건 있습니다. 해제하면 보호자에게 더 이상
// 알림이 가지 않습니다" 확인 창을 띄운다 — 재알림은 발송 시점의 보호자에게 가므로 해제 뒤에는 본인에게만 간다.
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class UnpairCheckResponse(
    val unconfirmedDangerCount: Int,
)
