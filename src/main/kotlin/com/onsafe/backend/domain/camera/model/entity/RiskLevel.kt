package com.onsafe.backend.domain.camera.model.entity

enum class RiskLevel(val label: String, val colorCode: String) {
    DANGER("위험", "#FF0000"),
    WARNING("주의", "#FFA500"),
    NORMAL("정상", "#00C853");

    companion object {
        const val DANGER_THRESHOLD = 75f
        const val WARNING_THRESHOLD = 50f

        fun fromLabel(label: String): RiskLevel {
            val lower = label.lowercase()
            return entries.find { lower == it.label || lower == it.name.lowercase() }
                ?: when (lower) {
                    "danger", "high" -> DANGER
                    "warning", "medium" -> WARNING
                    else -> NORMAL
                }
        }
    }
}
