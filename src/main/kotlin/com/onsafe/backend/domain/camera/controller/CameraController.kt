package com.onsafe.backend.domain.camera.controller

import com.onsafe.backend.common.exception.BusinessException
import com.onsafe.backend.common.exception.ErrorCode
import com.onsafe.backend.common.response.ApiResponse
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
class CameraController(private val cameraService: CameraService) {

    @Operation(summary = "현재 위험 점수 조회", security = [SecurityRequirement(name = "BearerAuth")])
    @GetMapping("/score/{userId}")
    suspend fun getRiskScore(
        @PathVariable userId: String,
        @AuthenticationPrincipal principal: String
    ): ApiResponse<RiskScoreResponse> {
        if (principal != userId) throw BusinessException(ErrorCode.FORBIDDEN)
        return ApiResponse.ok(cameraService.getRiskScore(userId))
    }

    @Operation(summary = "현재 위험 상태 조회 (정상/주의/위험)", security = [SecurityRequirement(name = "BearerAuth")])
    @GetMapping("/status/{userId}")
    suspend fun getRiskStatus(
        @PathVariable userId: String,
        @AuthenticationPrincipal principal: String
    ): ApiResponse<RiskStatusResponse> {
        if (principal != userId) throw BusinessException(ErrorCode.FORBIDDEN)
        return ApiResponse.ok(cameraService.getRiskStatus(userId))
    }
}
