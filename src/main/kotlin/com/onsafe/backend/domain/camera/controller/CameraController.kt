package com.onsafe.backend.domain.camera.controller

import com.onsafe.backend.common.response.ApiResponse
import com.onsafe.backend.common.security.AccessGuard
import com.onsafe.backend.domain.camera.model.dto.HeartbeatRequest
import com.onsafe.backend.domain.camera.model.dto.RiskScoreResponse
import com.onsafe.backend.domain.camera.model.dto.RiskStatusResponse
import com.onsafe.backend.domain.camera.service.CameraService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
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

    @Operation(
        summary = "카메라 앱 heartbeat (피보호자 앱 → 서버)",
        description = "피보호자 앱이 2분 주기로 호출해 자기 생존을 서버에 알림. userId 는 JWT 에서 " +
            "서버가 취해오므로 남의 상태 조작 불가. 6분 이상 미수신 시 워치독 잡이 오프라인으로 판정.",
        security = [SecurityRequirement(name = "BearerAuth")]
    )
    @PostMapping("/heartbeat")
    suspend fun heartbeat(
        @AuthenticationPrincipal principal: String,
        @Valid @RequestBody request: HeartbeatRequest
    ): ApiResponse<Unit> {
        cameraService.recordHeartbeat(principal, request.powerSaveMode)
        return ApiResponse.ok(message = "heartbeat recorded")
    }
}
