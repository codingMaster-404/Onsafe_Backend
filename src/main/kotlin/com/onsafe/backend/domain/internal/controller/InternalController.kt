package com.onsafe.backend.domain.internal.controller

import com.onsafe.backend.common.response.ApiResponse
import com.onsafe.backend.domain.internal.model.dto.SaveFallLogRequest
import com.onsafe.backend.domain.internal.model.dto.UpdateRealtimeRequest
import com.onsafe.backend.domain.internal.service.InternalService
import io.swagger.v3.oas.annotations.Operation
import org.springframework.web.bind.annotation.*

// Python AI 서버 전용 내부 API — JWT 인증 없이 로컬 네트워크에서만 호출
@RestController
@RequestMapping("/internal")
class InternalController(private val internalService: InternalService) {

    @Operation(summary = "실시간 위험 점수 업데이트 (AI 서버 전용)", security = [])
    @PostMapping("/realtime")
    suspend fun updateRealtime(@RequestBody req: UpdateRealtimeRequest): ApiResponse<Unit> {
        internalService.updateRealtime(req)
        return ApiResponse.ok("실시간 데이터 업데이트 완료")
    }

    @Operation(summary = "낙상 로그 저장 + fall=true 시 FCM 발송 (AI 서버 전용)", security = [])
    @PostMapping("/fall-log")
    suspend fun saveFallLog(@RequestBody req: SaveFallLogRequest): ApiResponse<Unit> {
        internalService.saveFallLog(req)
        return ApiResponse.ok("낙상 로그 저장 완료")
    }
}
