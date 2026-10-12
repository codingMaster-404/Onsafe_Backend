package com.onsafe.backend.domain.auth.model.entity

import java.time.LocalDateTime

/**
 * 보안 이벤트 이력(C6). 컬렉션 이름은 `login_history`지만 로그인 외 이벤트도 함께 담는다.
 *
 * [ipAddress]·[userAgent]는 **로그인 계열에서만** 채운다. 로그아웃·비밀번호 변경·탈퇴 경로에는
 * `ServerWebExchange`가 닿지 않아 전 경로에서 채우려면 서비스·컨트롤러 시그니처를 모두 바꿔야 한다.
 * 침해 조사에서 결정적인 값은 **로그인 시도의 IP**이고, 이후 행위는 "누가 언제 무엇을"이면 충분하다
 * (완료 문서 D36).
 *
 * [success]·[failReason]도 로그인 계열에서만 의미가 있다.
 *
 * [targetUserId]는 다른 사용자를 대상으로 한 행위에만 채운다 — 실시간 영상 열람(`LIVE_VIEW_*`)의 피보호자.
 * "내 영상을 누가 언제 봤나"는 `target_user_id`로 조회한다.
 */
data class LoginHistory(
    val historyId: String,
    val userId: String,
    val eventType: SecurityEventType,
    val ipAddress: String? = null,
    val userAgent: String? = null,
    val success: Boolean = true,
    val failReason: String? = null,
    val targetUserId: String? = null,
    val timestamp: LocalDateTime = LocalDateTime.now()
)
