package com.onsafe.backend.domain.camera.model.dto

import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.databind.annotation.JsonNaming

// 피보호자 앱이 자기 살아있음을 서버에 알리는 heartbeat 페이로드.
// userId 는 JWT principal 에서 서버가 직접 취해 오므로 요청 바디에 담지 않는다 — 담으면
// 클라이언트가 임의의 userId 로 남의 상태를 조작할 여지가 생긴다.
// timestamp 는 클라이언트 시계라 신뢰할 수 없으므로 서버가 수신 시각으로 덮어쓴다.
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class HeartbeatRequest(
    // 삼성 One UI 초절전 등에 진입했음을 앱이 감지해 함께 전송. 서버는 이 값을 워치독 판정에
    // 참고할 수 있고, 향후 "절전 상태라 감지 신뢰도 낮음" 배너 표시 등에도 활용 가능.
    val powerSaveMode: Boolean = false,
)