package com.onsafe.backend.domain.camera.model.entity

import java.time.LocalDateTime

data class RealtimeData(
    val userId: String,
    val score: Float = 0f,
    val level: String = "정상",
    val updatedAt: LocalDateTime = LocalDateTime.now(),
    // Heartbeat 관련 필드 — Python AI 파이프라인이 죽어도 앱 자체 생존은 별개로 감시하기 위함.
    // updatedAt 은 Python AI 가 점수 갱신 시 찍히는 값이고, lastHeartbeatAt 은 피보호자 앱이
    // 직접 찍는다. 두 시각이 별도인 이유는 AI 서버 다운과 앱 다운을 구분해서 알림하기 위해서.
    val lastHeartbeatAt: LocalDateTime? = null,
    val isOnline: Boolean = false,
    val powerSaveMode: Boolean = false,
    // 오프라인 알림을 보낸 마지막 시각. 재알림 스팸을 막기 위해 첫 오프라인 감지 시 1회만 발송하고,
    // 복구 후 다시 오프라인이 되기 전까지는 재발송하지 않는다(null 로 리셋됨).
    val lastOfflineNotifiedAt: LocalDateTime? = null,
)