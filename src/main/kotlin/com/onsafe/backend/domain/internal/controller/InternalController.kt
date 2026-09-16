package com.onsafe.backend.domain.internal.controller

import com.onsafe.backend.common.response.ApiResponse
import com.onsafe.backend.common.security.InternalAuthGuard
import com.onsafe.backend.domain.internal.model.dto.SaveFallLogRequest
import com.onsafe.backend.domain.internal.model.dto.UpdateRealtimeRequest
import com.onsafe.backend.domain.internal.service.InternalService
import io.swagger.v3.oas.annotations.Operation
import org.springframework.web.bind.annotation.*

// Python AI 서버 전용 내부 API.
// `/internal/**`는 JWT 필터를 타지 않고 Kotlin 서비스는 --allow-unauthenticated로 배포되므로,
// 헤더 검증이 없으면 인터넷에서 누구나 가짜 낙상 알림을 주입하거나(fall-log) 위험 점수를 0으로
// 덮어써 실제 위험을 가릴 수 있다(realtime). Cloud Scheduler 잡과 같은 X-Internal-Auth를 요구한다.
@RestController
@RequestMapping("/internal")
class InternalController(
    private val internalService: InternalService,
    private val internalAuthGuard: InternalAuthGuard
) {

    @Operation(summary = "실시간 위험 점수 업데이트 (AI 서버 전용)", security = [])
    @PostMapping("/realtime")
    suspend fun updateRealtime(
        @RequestHeader(value = "X-Internal-Auth", required = false) auth: String?,
        @RequestBody req: UpdateRealtimeRequest
    ): ApiResponse<Unit> {
        internalAuthGuard.require(auth)
        internalService.updateRealtime(req)
        return ApiResponse.ok("실시간 데이터 업데이트 완료")
    }

    @Operation(summary = "낙상 로그 저장 + fall=true 시 FCM 발송 (AI 서버 전용)", security = [])
    @PostMapping("/fall-log")
    suspend fun saveFallLog(
        @RequestHeader(value = "X-Internal-Auth", required = false) auth: String?,
        @RequestBody req: SaveFallLogRequest
    ): ApiResponse<Unit> {
        internalAuthGuard.require(auth)
        internalService.saveFallLog(req)
        return ApiResponse.ok("낙상 로그 저장 완료")
    }
}
