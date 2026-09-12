package com.onsafe.backend.domain.guardian.controller

import com.onsafe.backend.common.exception.BusinessException
import com.onsafe.backend.common.exception.ErrorCode
import com.onsafe.backend.common.response.ApiResponse
import com.onsafe.backend.common.util.clientIpAddress
import com.onsafe.backend.domain.guardian.model.dto.GuardianResponse
import com.onsafe.backend.domain.guardian.model.dto.PairRequest
import com.onsafe.backend.domain.guardian.model.dto.PairingCodeResponse
import com.onsafe.backend.domain.guardian.model.dto.PairingRequestResponse
import com.onsafe.backend.domain.guardian.model.dto.WardResponse
import com.onsafe.backend.domain.guardian.service.GuardianService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ServerWebExchange

@Tag(name = "Guardian", description = "보호자 페어링 API")
@RestController
@RequestMapping("/api/guardian")
class GuardianController(private val guardianService: GuardianService) {

    @Operation(
        summary = "페어링 코드 발급 (피보호자용)",
        description = "5분간 유효한 1회용 6자리 코드를 발급합니다. 재발급 시 이전 코드는 즉시 무효화됩니다.",
        security = [SecurityRequirement(name = "BearerAuth")]
    )
    @PostMapping("/{userId}/pairing-code")
    suspend fun issuePairingCode(
        @PathVariable userId: String,
        @AuthenticationPrincipal principal: String
    ): ApiResponse<PairingCodeResponse> {
        if (principal != userId) throw BusinessException(ErrorCode.FORBIDDEN)
        return ApiResponse.ok(guardianService.issuePairingCode(userId))
    }

    @Operation(
        summary = "페어링 코드로 연결 요청 (보호자용)",
        description = "피보호자가 발급한 코드를 입력해 연결을 요청합니다. 실제 관계 성립은 피보호자가 " +
            "승인해야 이뤄집니다 — 응답은 요청 식별자·만료 시각만 담고, 승인 결과는 별도 FCM(event=pairing_approved/rejected)으로 도착합니다.",
        security = [SecurityRequirement(name = "BearerAuth")]
    )
    @PostMapping("/{userId}/pair")
    suspend fun pair(
        @PathVariable userId: String,
        @AuthenticationPrincipal principal: String,
        @Valid @RequestBody request: PairRequest,
        exchange: ServerWebExchange
    ): ApiResponse<PairingRequestResponse> {
        if (principal != userId) throw BusinessException(ErrorCode.FORBIDDEN)
        return ApiResponse.ok(guardianService.pair(userId, request.code, exchange.clientIpAddress()), "연결 요청이 전송되었습니다.")
    }

    @Operation(
        summary = "페어링 요청 승인 (피보호자용)",
        description = "보호자로부터 온 연결 요청을 승인합니다. 승인 시 실제 관계가 생성되고 보호자에게 완료 FCM이 전송됩니다.",
        security = [SecurityRequirement(name = "BearerAuth")]
    )
    @PostMapping("/{userId}/pairing-requests/{requestId}/approve")
    suspend fun approvePairingRequest(
        @PathVariable userId: String,
        @PathVariable requestId: String,
        @AuthenticationPrincipal principal: String
    ): ApiResponse<WardResponse> {
        if (principal != userId) throw BusinessException(ErrorCode.FORBIDDEN)
        return ApiResponse.ok(guardianService.approvePairingRequest(userId, requestId), "보호자 연결이 완료되었습니다.")
    }

    @Operation(
        summary = "페어링 요청 거부 (피보호자용)",
        description = "보호자로부터 온 연결 요청을 거부합니다. 보호자에게 거부 FCM이 전송됩니다.",
        security = [SecurityRequirement(name = "BearerAuth")]
    )
    @PostMapping("/{userId}/pairing-requests/{requestId}/reject")
    suspend fun rejectPairingRequest(
        @PathVariable userId: String,
        @PathVariable requestId: String,
        @AuthenticationPrincipal principal: String
    ): ApiResponse<Unit> {
        if (principal != userId) throw BusinessException(ErrorCode.FORBIDDEN)
        guardianService.rejectPairingRequest(userId, requestId)
        return ApiResponse.ok(message = "연결 요청을 거부했습니다.")
    }

    @Operation(summary = "연결된 피보호자 조회 (보호자용)", security = [SecurityRequirement(name = "BearerAuth")])
    @GetMapping("/{userId}/wards")
    suspend fun getWards(
        @PathVariable userId: String,
        @AuthenticationPrincipal principal: String
    ): ApiResponse<Map<String, List<WardResponse>>> {
        if (principal != userId) throw BusinessException(ErrorCode.FORBIDDEN)
        return ApiResponse.ok(mapOf("wards" to guardianService.getWards(userId)))
    }

    @Operation(
        summary = "연결된 보호자 조회 (피보호자용)",
        description = "피보호자가 자기와 연결된 보호자를 확인합니다 — 감시받는 사람이 감시자를 감사할 수 있게 노출.",
        security = [SecurityRequirement(name = "BearerAuth")]
    )
    @GetMapping("/{userId}/my-guardian")
    suspend fun getMyGuardian(
        @PathVariable userId: String,
        @AuthenticationPrincipal principal: String
    ): ApiResponse<GuardianResponse?> {
        if (principal != userId) throw BusinessException(ErrorCode.FORBIDDEN)
        return ApiResponse.ok(guardianService.getMyGuardian(userId))
    }

    @Operation(
        summary = "보호자 연결 해제",
        description = "보호자·피보호자 어느 쪽에서 호출해도 관계가 해제됩니다. 상대방에게 해제 FCM이 전송됩니다.",
        security = [SecurityRequirement(name = "BearerAuth")]
    )
    @DeleteMapping("/{userId}/pair/{counterpartUserId}")
    suspend fun unpair(
        @PathVariable userId: String,
        @PathVariable counterpartUserId: String,
        @AuthenticationPrincipal principal: String
    ): ApiResponse<Unit> {
        if (principal != userId) throw BusinessException(ErrorCode.FORBIDDEN)
        guardianService.unpair(userId, counterpartUserId)
        return ApiResponse.ok(message = "연결이 해제되었습니다.")
    }
}
