package com.onsafe.backend.domain.consent.controller

import com.onsafe.backend.common.exception.BusinessException
import com.onsafe.backend.common.exception.ErrorCode
import com.onsafe.backend.common.response.ApiResponse
import com.onsafe.backend.domain.consent.model.dto.ConsentResponse
import com.onsafe.backend.domain.consent.service.ConsentService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
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
}
