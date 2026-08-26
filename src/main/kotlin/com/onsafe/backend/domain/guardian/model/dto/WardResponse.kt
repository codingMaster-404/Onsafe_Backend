package com.onsafe.backend.domain.guardian.model.dto

import com.onsafe.backend.domain.user.model.entity.User

data class WardResponse(
    val userId: String,
    val name: String
) {
    companion object {
        fun from(user: User) = WardResponse(userId = user.userId, name = user.name)
    }
}
