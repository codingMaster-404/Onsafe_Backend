package com.onsafe.backend.domain.settings.model.dto

import jakarta.validation.constraints.NotNull

data class MarketingConsentRequest(
    @field:NotNull(message = "동의 여부는 필수입니다.")
    val consent: Boolean?
)
