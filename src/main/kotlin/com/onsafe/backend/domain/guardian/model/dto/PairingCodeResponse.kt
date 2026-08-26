package com.onsafe.backend.domain.guardian.model.dto

data class PairingCodeResponse(
    val code: String,
    val expiresInSeconds: Long
)
