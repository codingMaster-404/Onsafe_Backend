package com.onsafe.backend.domain.live.model.dto

import java.time.LocalDateTime

/**
 * LiveKit 방 접속 정보. 보호자(시청)·피보호자(송출) 응답 형식이 같고 [token]의 권한만 다르다.
 * [expiresAt]은 세션 종료 예정 시각 — 앱은 이 시각에 화면을 닫고, 서버도 같은 시각에 방을 정리한다(W3).
 * [requestDelivered]는 보호자가 **새 세션**을 열 때만 채운다 — 피보호자 기기에 송출 요청(FCM)이 1대 이상 전달됐는지.
 * false면 앱은 기다리지 말고 바로 안내한다. 연장·송출 토큰 응답에선 null.
 */
data class LiveTokenResponse(
    val serverUrl: String,
    val room: String,
    val token: String,
    val expiresAt: LocalDateTime,
    val requestDelivered: Boolean? = null,
)
