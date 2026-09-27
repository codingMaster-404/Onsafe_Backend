package com.onsafe.backend.domain.consent.model.dto

import com.onsafe.backend.domain.consent.model.entity.ConsentType

// 재동의가 필요한 약관 한 건. [version]은 앱이 모달에 보여주고 동의 요청에 그대로 돌려보낼 현재 버전이다.
// [required]가 true면 동의 전까지 서버가 보호 API를 막을 수 있는 항목(서버 차단 스위치가 켜진 경우),
// false면 경미한 개정이라 확인만 받으면 되는 항목이다.
data class PendingConsentResponse(
    val type: ConsentType,
    val version: String,
    val required: Boolean,
)
