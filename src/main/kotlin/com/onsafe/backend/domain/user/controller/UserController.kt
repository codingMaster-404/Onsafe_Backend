package com.onsafe.backend.domain.user.controller

import com.onsafe.backend.common.exception.BusinessException
import com.onsafe.backend.common.exception.ErrorCode
import com.onsafe.backend.common.response.ApiResponse
import com.onsafe.backend.domain.auth.model.dto.FcmTokenRequest
import com.onsafe.backend.domain.notification.service.NotificationService
import com.onsafe.backend.domain.user.model.dto.DeleteUserRequest
import com.onsafe.backend.domain.user.model.dto.UserResponse
import com.onsafe.backend.domain.user.model.dto.UserUpdateRequest
import com.onsafe.backend.domain.user.model.dto.VerifyPasswordRequest
import com.onsafe.backend.domain.user.model.dto.VerifyPasswordResponse
import com.onsafe.backend.domain.user.service.UserService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*

@Tag(name = "User", description = "사용자 관련 API")
@RestController
@RequestMapping("/api/users")
class UserController(
    private val userService: UserService,
    private val notificationService: NotificationService
) {

    @Operation(summary = "사용자 정보 조회", security = [SecurityRequirement(name = "BearerAuth")])
    @GetMapping("/{userId}")
    suspend fun getUser(
        @PathVariable userId: String,
        @AuthenticationPrincipal principal: String
    ): ApiResponse<UserResponse> {
        if (principal != userId) throw BusinessException(ErrorCode.FORBIDDEN)
        return ApiResponse.ok(userService.getUser(userId))
    }

    @Operation(summary = "개인정보 수정", security = [SecurityRequirement(name = "BearerAuth")])
    @PutMapping("/{userId}")
    suspend fun updateUser(
        @PathVariable userId: String,
        @AuthenticationPrincipal principal: String,
        @Valid @RequestBody request: UserUpdateRequest
    ): ApiResponse<UserResponse> {
        if (principal != userId) throw BusinessException(ErrorCode.FORBIDDEN)
        return ApiResponse.ok(userService.updateUser(userId, request), "개인정보가 수정되었습니다.")
    }

    @Operation(summary = "비밀번호 사전 확인", security = [SecurityRequirement(name = "BearerAuth")])
    @PostMapping("/{userId}/verify-password")
    suspend fun verifyPassword(
        @PathVariable userId: String,
        @AuthenticationPrincipal principal: String,
        @Valid @RequestBody request: VerifyPasswordRequest
    ): ApiResponse<VerifyPasswordResponse> {
        if (principal != userId) throw BusinessException(ErrorCode.FORBIDDEN)
        val response = userService.verifyPassword(userId, request.currentPassword)
        return ApiResponse.ok(response, "비밀번호가 확인되었습니다.")
    }

    @Operation(summary = "FCM 토큰 등록", security = [SecurityRequirement(name = "BearerAuth")])
    @PostMapping("/{userId}/fcm-token")
    suspend fun registerFcmToken(
        @PathVariable userId: String,
        @AuthenticationPrincipal principal: String,
        @Valid @RequestBody request: FcmTokenRequest
    ): ApiResponse<Unit> {
        if (principal != userId) throw BusinessException(ErrorCode.FORBIDDEN)
        notificationService.registerFcmToken(userId, request.fcmToken, request.deviceId)
        return ApiResponse.ok(message = "FCM 토큰 등록 완료")
    }

    // 로그아웃 요청에 토큰을 실어 보내면(B2) 이 API를 따로 부를 필요가 없다. 기기 교체·앱 재설치처럼
    // 로그아웃 없이 정리해야 하는 경우를 위해 남겨 둔다. 요청 토큰과 저장된 토큰이 같을 때만 지운다.
    @Operation(summary = "FCM 토큰 해제", security = [SecurityRequirement(name = "BearerAuth")])
    @DeleteMapping("/{userId}/fcm-token")
    suspend fun unregisterFcmToken(
        @PathVariable userId: String,
        @AuthenticationPrincipal principal: String,
        @Valid @RequestBody request: FcmTokenRequest
    ): ApiResponse<Unit> {
        if (principal != userId) throw BusinessException(ErrorCode.FORBIDDEN)
        notificationService.unregisterFcmToken(userId, request.fcmToken, request.deviceId)
        return ApiResponse.ok(message = "FCM 토큰 해제 완료")
    }

    @Operation(summary = "회원 탈퇴", security = [SecurityRequirement(name = "BearerAuth")])
    @DeleteMapping("/{userId}")
    suspend fun deleteUser(
        @PathVariable userId: String,
        @AuthenticationPrincipal principal: String,
        @Valid @RequestBody request: DeleteUserRequest
    ): ApiResponse<Unit> {
        if (principal != userId) throw BusinessException(ErrorCode.FORBIDDEN)
        userService.deleteUser(userId, request.reauthTicket)
        return ApiResponse.ok(message = "회원 탈퇴가 완료되었습니다.")
    }
}
