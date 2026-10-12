package com.onsafe.backend.domain.guardian.model.dto

import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.databind.annotation.JsonNaming

/**
 * 페어링 승인 본문(선택). [liveVideoEnabled]는 승인 다이얼로그의 "실시간 영상 보기 허용" 선택 값(W9) —
 * 보내면 피보호자 설정(`live_video_enabled`)에 그대로 반영하고, 생략하면(구버전 앱 등) 기존 값을 건드리지 않는다.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class ApprovePairingRequest(
    val liveVideoEnabled: Boolean? = null
)
