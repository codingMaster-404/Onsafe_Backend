package com.onsafe.backend.domain.live.model.entity

import java.time.Instant

/**
 * 피보호자 1명당 1개의 실시간 영상 요청 상태. [expiresAt]이 지나면 끝난 세션이다(Redis TTL과 같은 시각).
 * [startedBy]는 처음 요청한 보호자 — 다른 연결된 보호자가 같은 세션에 함께 들어오거나 연장해도 바뀌지 않는다.
 */
data class LiveSession(
    val elderUserId: String,
    val startedBy: String,
    val expiresAt: Instant,
)
