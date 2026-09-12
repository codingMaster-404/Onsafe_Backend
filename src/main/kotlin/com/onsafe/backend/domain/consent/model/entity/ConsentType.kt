package com.onsafe.backend.domain.consent.model.entity

// 회원가입 시 반드시 동의해야 하는 필수 약관 3종. 마케팅 정보 수신 동의는 가입 후에도
// 설정에서 언제든 바뀌는 토글 값이라 User.marketingConsent로 별도 관리하고 여기 포함하지 않는다.
enum class ConsentType {
    TERMS_OF_SERVICE,
    PRIVACY_POLICY,
    SENSITIVE_INFO,
}

// 늘봄 약관 시행일자(https://jasmin527.github.io/onsafe_privacy_policy/) 기준 최초 버전.
// 배치 위치·재동의 플로우는 별도 분석 참고 — 지금은 코드 상수로 시작한다.
const val CURRENT_CONSENT_VERSION = "2026-01-01"
