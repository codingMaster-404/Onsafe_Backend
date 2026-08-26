package com.onsafe.backend.domain.guardian.model.entity

import java.time.LocalDateTime

data class GuardianLink(
    val guardianUserId: String,
    val elderUserId: String,
    val createdAt: LocalDateTime = LocalDateTime.now()
)
