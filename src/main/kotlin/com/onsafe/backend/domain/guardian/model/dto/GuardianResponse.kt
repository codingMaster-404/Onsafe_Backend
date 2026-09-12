package com.onsafe.backend.domain.guardian.model.dto

import com.onsafe.backend.domain.user.model.entity.User

// 피보호자가 자기 보호자 정보를 확인할 때 반환. 1:1 정책상 항상 최대 한 명이므로 List 대신 단일
// 객체(또는 null)로 리턴한다.
data class GuardianResponse(
    val userId: String,
    val name: String,
) {
    companion object {
        fun from(user: User) = GuardianResponse(userId = user.userId, name = user.name)
    }
}