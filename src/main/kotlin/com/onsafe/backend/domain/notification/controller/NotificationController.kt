package com.onsafe.backend.domain.notification.controller

import com.onsafe.backend.common.exception.BusinessException
import com.onsafe.backend.common.exception.ErrorCode
import com.onsafe.backend.common.response.ApiResponse
import com.onsafe.backend.domain.notification.model.dto.NotificationLogResponse
import com.onsafe.backend.domain.notification.service.NotificationService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*

@Tag(name = "Notification", description = "알림 목록 API")
@RestController
@RequestMapping("/api/notifications")
class NotificationController(private val notificationService: NotificationService) {

    @Operation(
        summary = "알림 목록 조회",
        description = "본인이 수신한 알림만 조회합니다. 보호자는 연결된 피보호자에게 발생한 알림도 " +
            "본인 인박스에 함께 쌓이므로 별도 대상 지정 없이 본인 목록만 조회하면 됩니다.",
        security = [SecurityRequirement(name = "BearerAuth")]
    )
    @GetMapping("/{userId}")
    suspend fun getNotifications(
        @PathVariable userId: String,
        @AuthenticationPrincipal principal: String
    ): ApiResponse<Map<String, List<NotificationLogResponse>>> {
        if (principal != userId) throw BusinessException(ErrorCode.FORBIDDEN)
        return ApiResponse.ok(mapOf("notifications" to notificationService.getNotifications(userId)))
    }

    @Operation(summary = "알림 읽음 처리", security = [SecurityRequirement(name = "BearerAuth")])
    @PatchMapping("/{userId}/{notificationId}/read")
    suspend fun markRead(
        @PathVariable userId: String,
        @PathVariable notificationId: String,
        @AuthenticationPrincipal principal: String
    ): ApiResponse<NotificationLogResponse> {
        if (principal != userId) throw BusinessException(ErrorCode.FORBIDDEN)
        return ApiResponse.ok(notificationService.markRead(userId, notificationId), "알림을 읽음 처리했습니다.")
    }
}
