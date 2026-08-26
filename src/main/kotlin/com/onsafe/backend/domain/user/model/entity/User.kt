package com.onsafe.backend.domain.user.model.entity

import java.time.LocalDateTime

data class User(
    val userId: String,
    val password: String,
    val name: String,
    val phone: String,
    val mail: String,
    val address: String? = null,
    val addressDetail: String? = null,
    val fcmToken: String? = null,
    val createdAt: LocalDateTime = LocalDateTime.now(),
    // 마케팅 수신 동의 이력은 회원 프로파일에 함께 보존한다. 동의/철회 시점을 각각 남겨
    // 감사(audit) 및 재동의 유도 시 활용한다. withdrawnAt이 채워지면 consent는 false여야 한다.
    val marketingConsent: Boolean = false,
    val marketingConsentAt: LocalDateTime? = null,
    val marketingConsentWithdrawnAt: LocalDateTime? = null,
)
