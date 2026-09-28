package com.onsafe.backend.domain.settings.model.dto

import com.onsafe.backend.domain.settings.model.entity.UserSettings
import com.onsafe.backend.domain.user.model.entity.User
import java.time.LocalDateTime

data class NotificationSettingsResponse(
    val notificationEnabled: Boolean,
    val soundEnabled: Boolean,
    val vibrationEnabled: Boolean,
) {
    companion object {
        fun from(s: UserSettings) = NotificationSettingsResponse(
            notificationEnabled = s.notificationEnabled,
            soundEnabled = s.soundEnabled,
            vibrationEnabled = s.vibrationEnabled,
        )
    }
}

data class RetentionSettingsResponse(val retentionDays: Int = 30)

// 필드명은 API 스펙(`consented_at`)을 따른다 — 이전 이름 consentAt은 `consent_at`으로 직렬화돼
// 스펙대로 구현한 앱이 동의 시각을 항상 null로 받았다(C3). withdrawn_at은 스펙 외 추가 필드다.
data class MarketingConsentResponse(
    val consent: Boolean,
    val consentedAt: LocalDateTime?,
    val withdrawnAt: LocalDateTime?,
) {
    companion object {
        fun from(u: User) = MarketingConsentResponse(
            consent = u.marketingConsent,
            consentedAt = u.marketingConsentAt,
            withdrawnAt = u.marketingConsentWithdrawnAt,
        )
    }
}
