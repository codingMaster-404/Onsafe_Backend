package com.onsafe.backend.domain.camera.controller

import com.onsafe.backend.common.response.ApiResponse
import com.onsafe.backend.common.security.AccessGuard
import com.onsafe.backend.domain.camera.model.dto.RiskScoreResponse
import com.onsafe.backend.domain.camera.model.dto.RiskStatusResponse
import com.onsafe.backend.domain.camera.service.CameraService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*

@Tag(name = "Camera", description = "카메라 & 실시간 모니터링 API")
@RestController
@RequestMapping("/api/camera")
class CameraController(
    private val cameraService: CameraService,
    private val accessGuard: AccessGuard
) {

    @Operation(
        summary = "현재 위험 점수 조회",
        description = "본인 또는 연결된 보호자만 조회 가능.",
        security = [SecurityRequirement(name = "BearerAuth")]
    )
    @GetMapping("/score/{userId}")
    suspend fun getRiskScore(
        @PathVariable userId: String,
        @AuthenticationPrincipal principal: String
    ): ApiResponse<RiskScoreResponse> {
        accessGuard.requireOwnerOrGuardian(principal, userId)
        return ApiResponse.ok(cameraService.getRiskScore(userId))
    }

    @Operation(
        summary = "현재 위험 상태 조회 (정상/주의/위험)",
        description = "본인 또는 연결된 보호자만 조회 가능.",
        security = [SecurityRequirement(name = "BearerAuth")]
    )
    @GetMapping("/status/{userId}")
    suspend fun getRiskStatus(
        @PathVariable userId: String,
        @AuthenticationPrincipal principal: String
    ): ApiResponse<RiskStatusResponse> {
        accessGuard.requireOwnerOrGuardian(principal, userId)
        return ApiResponse.ok(cameraService.getRiskStatus(userId))
    }
}
