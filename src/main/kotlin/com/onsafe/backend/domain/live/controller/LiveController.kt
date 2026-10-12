package com.onsafe.backend.domain.live.controller

import com.onsafe.backend.common.response.ApiResponse
import com.onsafe.backend.domain.live.model.dto.LiveTokenResponse
import com.onsafe.backend.domain.live.service.LiveService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Tag(name = "Live", description = "보호자 실시간 영상(LiveKit) API")
@RestController
@RequestMapping("/api/live")
class LiveController(private val liveService: LiveService) {

    @Operation(
        summary = "실시간 영상 시청 시작·연장 (보호자) — 시청 전용 토큰 발급",
        security = [SecurityRequirement(name = "BearerAuth")]
    )
    @PostMapping("/{elderUserId}/session")
    suspend fun startSession(
        @PathVariable elderUserId: String,
        @AuthenticationPrincipal principal: String
    ): ApiResponse<LiveTokenResponse> =
        ApiResponse.ok(liveService.startSession(principal, elderUserId))

    @Operation(summary = "실시간 영상 시청 종료 (보호자)", security = [SecurityRequirement(name = "BearerAuth")])
    @DeleteMapping("/{elderUserId}/session")
    suspend fun endSession(
        @PathVariable elderUserId: String,
        @AuthenticationPrincipal principal: String
    ): ApiResponse<Unit> {
        liveService.endSession(principal, elderUserId)
        return ApiResponse.ok(message = "실시간 영상 종료")
    }

    @Operation(
        summary = "실시간 영상 송출 토큰 (피보호자) — 진행 중 요청이 있을 때만",
        security = [SecurityRequirement(name = "BearerAuth")]
    )
    @PostMapping("/me/publish-token")
    suspend fun issuePublishToken(
        @AuthenticationPrincipal principal: String
    ): ApiResponse<LiveTokenResponse> =
        ApiResponse.ok(liveService.issuePublishToken(principal))
}
