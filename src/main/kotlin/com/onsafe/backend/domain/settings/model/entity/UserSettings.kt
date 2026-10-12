package com.onsafe.backend.domain.settings.model.entity

import java.time.LocalDateTime

// 마케팅 수신 동의는 users 컬렉션으로 이관됨 (users.marketing_consent / marketing_consent_at /
// marketing_consent_withdrawn_at). 알림 관련 토글과 실시간 영상 동의만 여기에 둔다.
data class UserSettings(
    val userId: String,
    val notificationEnabled: Boolean = true,
    val soundEnabled: Boolean = true,
    val vibrationEnabled: Boolean = true,
    // 보호자 실시간 영상(LIVE) 송출 동의 — 거부해도 서비스를 쓸 수 있는 선택 동의라 필수 약관(ConsentType)과 분리한다.
    // 켜기 전에는 보호자가 영상을 요청해도 송출하지 않는다(기본 꺼짐). 시각은 동의·철회 전환 때만 기록한다.
    val liveVideoEnabled: Boolean = false,
    val liveVideoConsentedAt: LocalDateTime? = null,
    val liveVideoWithdrawnAt: LocalDateTime? = null,
)
