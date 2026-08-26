package com.onsafe.backend.domain.logs.controller

import com.onsafe.backend.common.exception.BusinessException
import com.onsafe.backend.common.exception.ErrorCode
import com.onsafe.backend.common.response.ApiResponse
import com.onsafe.backend.domain.guardian.repository.GuardianLinkRepository
import com.onsafe.backend.domain.logs.model.dto.FallLogResponse
import com.onsafe.backend.domain.logs.service.FallLogService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*

@Tag(name = "FallLogs", description = "낙상 로그 API")
@RestController
@RequestMapping("/api/fall-logs")
class FallLogController(
    private val fallLogService: FallLogService,
    private val guardianLinkRepository: GuardianLinkRepository
) {

    // 조회·확인 처리는 본인 또는 guardian_links로 연결된 보호자까지 허용한다.
    // 삭제·영상 업로드는 카메라 기기를 쥔 본인 계정만 가능해야 하므로 이 헬퍼를 쓰지 않는다.
    private suspend fun requireOwnerOrGuardian(principal: String, userId: String) {
        if (principal == userId) return
        if (guardianLinkRepository.exists(principal, userId)) return
        throw BusinessException(ErrorCode.FORBIDDEN)
    }

    @Operation(
        summary = "낙상 로그 목록 조회",
        description = "level 파라미터로 필터링 가능. 값: 위험 | 주의 (생략 시 전체). 본인 또는 연결된 보호자만 조회 가능.",
        security = [SecurityRequirement(name = "BearerAuth")]
    )
    @GetMapping("/{userId}")
    suspend fun getLogs(
        @PathVariable userId: String,
        @AuthenticationPrincipal principal: String,
        @RequestParam(required = false) level: String?
    ): ApiResponse<Map<String, List<FallLogResponse>>> {
        requireOwnerOrGuardian(principal, userId)
        return ApiResponse.ok(mapOf("logs" to fallLogService.getLogs(userId, level)))
    }

    @Operation(summary = "낙상 로그 탭별 건수 조회", security = [SecurityRequirement(name = "BearerAuth")])
    @GetMapping("/{userId}/counts")
    suspend fun getLogCounts(
        @PathVariable userId: String,
        @AuthenticationPrincipal principal: String
    ): ApiResponse<Map<String, Int>> {
        requireOwnerOrGuardian(principal, userId)
        return ApiResponse.ok(fallLogService.getLogCounts(userId))
    }

    @Operation(summary = "낙상 로그 상세 조회", security = [SecurityRequirement(name = "BearerAuth")])
    @GetMapping("/{userId}/{logId}")
    suspend fun getLog(
        @PathVariable userId: String,
        @PathVariable logId: String,
        @AuthenticationPrincipal principal: String
    ): ApiResponse<FallLogResponse> {
        requireOwnerOrGuardian(principal, userId)
        return ApiResponse.ok(fallLogService.getLog(userId, logId))
    }

    @Operation(
        summary = "낙상 이벤트 확인 처리",
        description = "본인 또는 연결된 보호자가 확인 처리할 수 있습니다 — 피보호자가 응답 불가한 상황을 위해 보호자에게도 허용합니다.",
        security = [SecurityRequirement(name = "BearerAuth")]
    )
    @PatchMapping("/{userId}/{logId}/confirm")
    suspend fun confirmLog(
        @PathVariable userId: String,
        @PathVariable logId: String,
        @AuthenticationPrincipal principal: String
    ): ApiResponse<FallLogResponse> {
        requireOwnerOrGuardian(principal, userId)
        return ApiResponse.ok(fallLogService.confirmLog(userId, logId), "낙상 이벤트를 확인 처리했습니다.")
    }

    @Operation(summary = "낙상 로그 삭제", security = [SecurityRequirement(name = "BearerAuth")])
    @DeleteMapping("/{userId}/{logId}")
    suspend fun deleteLog(
        @PathVariable userId: String,
        @PathVariable logId: String,
        @AuthenticationPrincipal principal: String
    ): ApiResponse<Unit> {
        if (principal != userId) throw BusinessException(ErrorCode.FORBIDDEN)
        fallLogService.deleteLog(userId, logId)
        return ApiResponse.ok(message = "낙상 로그가 삭제되었습니다.")
    }

    @Operation(
        summary = "낙상 동영상 signed URL 조회 (#6)",
        description = "1시간 유효한 signed URL을 반환합니다. 동영상이 없으면 404를 반환합니다. 본인 또는 연결된 보호자만 조회 가능.",
        security = [SecurityRequirement(name = "BearerAuth")]
    )
    @GetMapping("/{userId}/{logId}/video")
    suspend fun getVideo(
        @PathVariable userId: String,
        @PathVariable logId: String,
        @AuthenticationPrincipal principal: String
    ): ApiResponse<Map<String, String>> {
        requireOwnerOrGuardian(principal, userId)
        val signedUrl = fallLogService.getSignedUrl(userId, logId)
        return ApiResponse.ok(mapOf("signed_url" to signedUrl))
    }

    @Operation(
        summary = "낙상 동영상 업로드용 signed URL 발급 (#14)",
        description = "Android가 GCS에 mp4 클립을 직접 업로드할 수 있도록 10분 유효한 signed PUT URL을 반환합니다. " +
            "업로드 요청은 반드시 Content-Type: video/mp4 헤더를 포함해야 합니다.",
        security = [SecurityRequirement(name = "BearerAuth")]
    )
    @PostMapping("/{userId}/{logId}/upload-url")
    suspend fun getUploadUrl(
        @PathVariable userId: String,
        @PathVariable logId: String,
        @AuthenticationPrincipal principal: String
    ): ApiResponse<Map<String, String>> {
        if (principal != userId) throw BusinessException(ErrorCode.FORBIDDEN)
        val uploadUrl = fallLogService.getUploadUrl(userId, logId)
        return ApiResponse.ok(mapOf("upload_url" to uploadUrl, "content_type" to "video/mp4"))
    }

    @Operation(
        summary = "낙상 동영상 업로드 완료 콜백 (#15)",
        description = "Android가 signed PUT URL로 GCS 업로드를 마친 뒤 호출합니다. " +
            "GCS에 실제 객체가 존재하는지 서버가 재확인한 뒤에만 video_url을 반영하며, 객체가 없으면 404를 반환합니다.",
        security = [SecurityRequirement(name = "BearerAuth")]
    )
    @PatchMapping("/{userId}/{logId}/video-complete")
    suspend fun completeVideoUpload(
        @PathVariable userId: String,
        @PathVariable logId: String,
        @AuthenticationPrincipal principal: String
    ): ApiResponse<FallLogResponse> {
        if (principal != userId) throw BusinessException(ErrorCode.FORBIDDEN)
        return ApiResponse.ok(fallLogService.completeVideoUpload(userId, logId), "동영상 업로드가 반영되었습니다.")
    }

}
