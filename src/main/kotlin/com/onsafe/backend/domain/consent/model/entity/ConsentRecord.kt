package com.onsafe.backend.domain.consent.model.entity

import java.time.LocalDateTime

// 필수 약관 동의는 철회 개념이 없는 1회성 법적 의사표시라, 상태를 덮어쓰지 않고
// 동의가 일어날 때마다 새 레코드를 남기는 append-only 이력으로 설계한다.
data class ConsentRecord(
    val recordId: String = "",
    val userId: String,
    val type: ConsentType,
    val version: String,
    val agreedAt: LocalDateTime,
)
