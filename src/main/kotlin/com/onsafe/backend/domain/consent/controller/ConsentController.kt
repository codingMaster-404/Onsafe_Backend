package com.onsafe.backend.domain.consent.controller

import com.onsafe.backend.common.exception.BusinessException
import com.onsafe.backend.common.exception.ErrorCode
import com.onsafe.backend.common.response.ApiResponse
import com.onsafe.backend.domain.consent.model.dto.ConsentAgreeRequest
import com.onsafe.backend.domain.consent.model.dto.ConsentResponse
import com.onsafe.backend.domain.consent.model.dto.PendingConsentResponse
import com.onsafe.backend.domain.consent.service.ConsentService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Tag(name = "Consent", description = "필수 약관 동의 이력 API")
@RestController
@RequestMapping("/api/consents")
class ConsentController(private val consentService: ConsentService) {

    @Operation(summary = "필수 약관 동의 이력 조회", security = [SecurityRequirement(name = "BearerAuth")])
    @GetMapping("/{userId}")
    suspend fun getConsents(
        @PathVariable userId: String,
        @AuthenticationPrincipal principal: String
    ): ApiResponse<List<ConsentResponse>> {
        if (principal != userId) throw BusinessException(ErrorCode.FORBIDDEN)
        return ApiResponse.ok(consentService.getConsents(userId))
    }

    // 로그인 응답의 pending_consents와 같은 목록. 자동 로그인(로그인 API를 거치지 않는 진입)에서 쓴다.
    // 재동의 전 차단(cr 클레임) 중에도 호출할 수 있도록 SecurityPaths.CONSENT_EXEMPT에 포함된다.
    @Operation(summary = "재동의가 필요한 필수 약관 목록", security = [SecurityRequirement(name = "BearerAuth")])
    @GetMapping("/{userId}/pending")
    suspend fun getPendingConsents(
        @PathVariable userId: String,
        @AuthenticationPrincipal principal: String
    ): ApiResponse<List<PendingConsentResponse>> {
        if (principal != userId) throw BusinessException(ErrorCode.FORBIDDEN)
        return ApiResponse.ok(consentService.getPending(userId))
    }

    // 응답은 남은 재동의 목록이다. 비어 있으면 앱이 /api/auth/refresh로 cr 클레임 없는 토큰을 받아야
    // 차단이 풀린다 — access 토큰에는 auth_time이 없어 여기서 새 토큰을 발급할 수 없다(D2).
    @Operation(summary = "개정 약관 재동의", security = [SecurityRequirement(name = "BearerAuth")])
    @PostMapping("/{userId}")
    suspend fun agree(
        @PathVariable userId: String,
        @AuthenticationPrincipal principal: String,
        @Valid @RequestBody request: ConsentAgreeRequest
    ): ApiResponse<List<PendingConsentResponse>> {
        if (principal != userId) throw BusinessException(ErrorCode.FORBIDDEN)
        return ApiResponse.ok(consentService.agree(userId, request), "약관 동의가 저장되었습니다.")
    }
}
