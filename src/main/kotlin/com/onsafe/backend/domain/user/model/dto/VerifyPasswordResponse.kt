package com.onsafe.backend.domain.user.model.dto

// verify-password 성공 시 발급되는 재인증 티켓(B1). 개인정보 수정·탈퇴 요청이 이 티켓을 첨부해야
// 서버가 "본인이 비밀번호를 방금 확인했다"를 알 수 있다 — 지금까지 저장 요청에는 비밀번호도 티켓도
// 없어서 확인이 앱 화면에서만 이뤄졌다(EditProfileViewModel).
data class VerifyPasswordResponse(
    val reauthTicket: String
)
