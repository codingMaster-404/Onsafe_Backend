package com.onsafe.backend.domain.settings.model.entity

// 마케팅 수신 동의는 users 컬렉션으로 이관됨 (users.marketing_consent / marketing_consent_at /
// marketing_consent_withdrawn_at). 알림 관련 토글만 여기에 남긴다.
data class UserSettings(
    val userId: String,
    val notificationEnabled: Boolean = true,
    val soundEnabled: Boolean = true,
    val vibrationEnabled: Boolean = true,
)
