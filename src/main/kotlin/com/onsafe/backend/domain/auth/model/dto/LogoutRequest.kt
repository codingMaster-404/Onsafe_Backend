package com.onsafe.backend.domain.auth.model.dto

import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.databind.annotation.JsonNaming

// 로그아웃 요청의 선택 본문(B2). 앱이 로그아웃 API를 부른 뒤 따로 해제 API를 호출하면 그 시점엔
// access 토큰이 이미 블랙리스트라 항상 401이고 서버에는 토큰이 남는다. 로그아웃 한 번에 함께
// 처리하면 순서 문제가 사라지고, 카메라 모드처럼 해제 호출이 없는 경로도 같이 정리된다.
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class LogoutRequest(
    val fcmToken: String? = null,
    val deviceId: String? = null
)
