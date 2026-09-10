package com.onsafe.backend.domain.consent.model.dto

import com.onsafe.backend.domain.consent.model.entity.ConsentRecord
import com.onsafe.backend.domain.consent.model.entity.ConsentType
import java.time.LocalDateTime

data class ConsentResponse(
    val type: ConsentType,
    val version: String,
    val agreedAt: LocalDateTime,
) {
    companion object {
        fun from(record: ConsentRecord) = ConsentResponse(
            type = record.type,
            version = record.version,
            agreedAt = record.agreedAt,
        )
    }
}
