package com.onsafe.backend.domain.user.model.dto

import com.onsafe.backend.domain.user.model.entity.User
import java.time.LocalDateTime

data class UserResponse(
    val userId: String,
    val name: String,
    val mail: String,
    val phone: String,
    val address: String?,
    val addressDetail: String?,
    val createdAt: LocalDateTime
) {
    companion object {
        fun from(user: User) = UserResponse(
            userId = user.userId,
            name = user.name,
            mail = user.mail,
            phone = user.phone,
            address = user.address,
            addressDetail = user.addressDetail,
            createdAt = user.createdAt
        )
    }
}
