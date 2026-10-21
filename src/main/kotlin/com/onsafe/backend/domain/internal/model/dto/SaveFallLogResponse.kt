package com.onsafe.backend.domain.internal.model.dto

// /internal/fall-log 응답. saved=false면 계정 없음·연결된 보호자 없음으로 저장을 건너뛴 것이다 —
// Python은 이때 앱에 log_id를 내려 주지 않는다(없는 기록에 영상 업로드를 시도하지 않게).
// Python이 읽는 서비스 간 계약이라 필드 이름을 바꾸면 app/domain/camera/service.py도 함께 바꿔야 한다.
data class SaveFallLogResponse(
    val saved: Boolean
)
