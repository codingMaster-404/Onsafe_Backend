package com.onsafe.backend.domain.consent.model.entity

// 회원가입 시 반드시 동의해야 하는 필수 약관 3종. 마케팅 정보 수신 동의는 가입 후에도
// 설정에서 언제든 바뀌는 토글 값이라 User.marketingConsent로 별도 관리하고 여기 포함하지 않는다.
enum class ConsentType {
    TERMS_OF_SERVICE,
    PRIVACY_POLICY,
    SENSITIVE_INFO,
}

/**
 * 약관 타입별 현재 시행 버전.
 * [requiresReconsent]가 false인 개정(문구 정리 등 경미한 변경)은 재동의 목록에는 나오지만
 * 서버 차단(access 토큰 `cr` 클레임) 대상이 아니다 — 개인정보 수집 항목·목적 확대처럼 별도 동의가
 * 필요한 개정에만 true로 둔다(K4).
 */
data class ConsentPolicy(
    val version: String,
    val requiresReconsent: Boolean,
)

// 늘봄 약관 시행일자(https://jasmin527.github.io/onsafe_privacy_policy/) 기준 최초 버전.
// 약관을 개정하면 해당 타입의 version을 새 시행일로 올린다 — 이전 버전에 동의한 사용자는
// 로그인·refresh 때 재동의 대상으로 계산된다(ConsentService.getPending).
val CONSENT_POLICIES: Map<ConsentType, ConsentPolicy> = mapOf(
    ConsentType.TERMS_OF_SERVICE to ConsentPolicy(version = "2026-01-01", requiresReconsent = true),
    ConsentType.PRIVACY_POLICY to ConsentPolicy(version = "2026-01-01", requiresReconsent = true),
    ConsentType.SENSITIVE_INFO to ConsentPolicy(version = "2026-01-01", requiresReconsent = true),
)
