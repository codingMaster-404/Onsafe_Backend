package com.onsafe.backend.domain.user.model.dto

import jakarta.validation.constraints.NotBlank

// 탈퇴 요청 본문(B6). verify-password가 발급한 재인증 티켓을 실어 보낸다 — 토큰만으로 되돌릴 수 없는
// 삭제가 실행되던 것을 막는다. DELETE + 본문 형태는 FCM 토큰 해제와 같은 방식이다(프론트 @HTTP hasBody).
data class DeleteUserRequest(
    @field:NotBlank(message = "본인 확인이 필요합니다. 비밀번호를 다시 확인해주세요.")
    val reauthTicket: String
)
