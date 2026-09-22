package com.onsafe.backend.domain.notification.model.entity

import java.time.LocalDateTime

// 기기 하나의 FCM 등록 토큰. deviceId는 앱이 보내는 ANDROID_ID(LoginRequest와 같은 값)로,
// 같은 계정의 여러 기기를 구분하는 키다.
data class FcmToken(
    val deviceId: String,
    val token: String,
    val updatedAt: LocalDateTime = LocalDateTime.now(),
)
