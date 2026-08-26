package com.onsafe.backend.domain.guardian.model.dto

import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.databind.annotation.JsonNaming
import jakarta.validation.constraints.NotBlank

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class PairRequest(
    @field:NotBlank(message = "페어링 코드를 입력해주세요.")
    val code: String
)
