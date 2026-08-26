package com.onsafe.backend.domain.settings.controller

import com.onsafe.backend.common.exception.BusinessException
import com.onsafe.backend.common.exception.ErrorCode
import com.onsafe.backend.common.response.ApiResponse
import com.onsafe.backend.domain.settings.model.dto.MarketingConsentRequest
import com.onsafe.backend.domain.settings.model.dto.MarketingConsentResponse
import com.onsafe.backend.domain.settings.model.dto.NotificationSettingsRequest
import com.onsafe.backend.domain.settings.model.dto.NotificationSettingsResponse
import com.onsafe.backend.domain.settings.model.dto.RetentionSettingsResponse
import com.onsafe.backend.domain.settings.service.SettingsService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*

@Tag(name = "Settings", description = "설정 API")
@RestController
@RequestMapping("/api/settings")
class SettingsController(private val settingsService: SettingsService) {

    @Operation(summary = "알림 설정 조회", security = [SecurityRequirement(name = "BearerAuth")])
    @GetMapping("/notifications/{userId}")
    suspend fun getNotificationSettings(
        @PathVariable userId: String,
        @AuthenticationPrincipal principal: String
    ): ApiResponse<NotificationSettingsResponse> {
        if (principal != userId) throw BusinessException(ErrorCode.FORBIDDEN)
        return ApiResponse.ok(settingsService.getNotificationSettings(userId))
    }

    @Operation(summary = "알림 설정 변경", security = [SecurityRequirement(name = "BearerAuth")])
    @PutMapping("/notifications/{userId}")
    suspend fun updateNotifications(
        @PathVariable userId: String,
        @Valid @RequestBody request: NotificationSettingsRequest,
        @AuthenticationPrincipal principal: String
    ): ApiResponse<NotificationSettingsResponse> {
        if (principal != userId) throw BusinessException(ErrorCode.FORBIDDEN)
        return ApiResponse.ok(settingsService.updateNotifications(userId, request), "알림 설정 변경 완료")
    }

    @Operation(summary = "로그 보관 기간 조회", security = [SecurityRequirement(name = "BearerAuth")])
    @GetMapping("/retention/{userId}")
    suspend fun getRetentionSettings(
        @PathVariable userId: String,
        @AuthenticationPrincipal principal: String
    ): ApiResponse<RetentionSettingsResponse> {
        if (principal != userId) throw BusinessException(ErrorCode.FORBIDDEN)
        return ApiResponse.ok(settingsService.getRetentionSettings(userId))
    }

    @Operation(summary = "마케팅 수신 동의 조회", security = [SecurityRequirement(name = "BearerAuth")])
    @GetMapping("/marketing/{userId}")
    suspend fun getMarketingConsent(
        @PathVariable userId: String,
        @AuthenticationPrincipal principal: String
    ): ApiResponse<MarketingConsentResponse> {
        if (principal != userId) throw BusinessException(ErrorCode.FORBIDDEN)
        return ApiResponse.ok(settingsService.getMarketingConsent(userId))
    }

    @Operation(summary = "마케팅 수신 동의 변경", security = [SecurityRequirement(name = "BearerAuth")])
    @PutMapping("/marketing/{userId}")
    suspend fun updateMarketingConsent(
        @PathVariable userId: String,
        @Valid @RequestBody request: MarketingConsentRequest,
        @AuthenticationPrincipal principal: String
    ): ApiResponse<MarketingConsentResponse> {
        if (principal != userId) throw BusinessException(ErrorCode.FORBIDDEN)
        return ApiResponse.ok(settingsService.updateMarketingConsent(userId, request), "마케팅 수신 동의 변경 완료")
    }
}
