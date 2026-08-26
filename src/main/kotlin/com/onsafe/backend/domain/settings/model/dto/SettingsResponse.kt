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

data class MarketingConsentResponse(
    val consent: Boolean,
    val consentAt: LocalDateTime?,
    val withdrawnAt: LocalDateTime?,
) {
    companion object {
        fun from(u: User) = MarketingConsentResponse(
            consent = u.marketingConsent,
            consentAt = u.marketingConsentAt,
            withdrawnAt = u.marketingConsentWithdrawnAt,
        )
    }
}
