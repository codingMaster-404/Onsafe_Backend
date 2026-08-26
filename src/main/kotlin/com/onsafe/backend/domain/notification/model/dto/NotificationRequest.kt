package com.onsafe.backend.domain.notification.model.dto

import jakarta.validation.constraints.NotBlank

data class NotificationRequest(
    @field:NotBlank val userId: String,
    @field:NotBlank val title: String,
    @field:NotBlank val body: String,
    // 알림 목록(NotificationLogResponse) 영속화용 — FCM data 페이로드(아래 data)와는 별개로,
    // 클라이언트가 FallLogResponse와 동일하게 score/fall로 FALL·WARNING을 직접 판별할 수 있게 한다.
    val logId: String? = null,
    val score: Float? = null,
    val fall: Boolean = false,
    val data: Map<String, String>? = null
)
