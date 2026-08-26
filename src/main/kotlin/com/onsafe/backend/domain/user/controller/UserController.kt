package com.onsafe.backend.domain.user.controller

import com.onsafe.backend.common.exception.BusinessException
import com.onsafe.backend.common.exception.ErrorCode
import com.onsafe.backend.common.response.ApiResponse
import com.onsafe.backend.domain.auth.model.dto.FcmTokenUpdateRequest
import com.onsafe.backend.domain.auth.service.AuthService
import com.onsafe.backend.domain.user.model.dto.UserResponse
import com.onsafe.backend.domain.user.model.dto.UserUpdateRequest
import com.onsafe.backend.domain.user.model.dto.VerifyPasswordRequest
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
    private val authService: AuthService
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
        @RequestBody request: VerifyPasswordRequest
    ): ApiResponse<Unit> {
        if (principal != userId) throw BusinessException(ErrorCode.FORBIDDEN)
        userService.verifyPassword(userId, request.currentPassword)
        return ApiResponse.ok(message = "비밀번호가 확인되었습니다.")
    }

    @Operation(summary = "FCM 토큰 등록/갱신", security = [SecurityRequirement(name = "BearerAuth")])
    @PutMapping("/{userId}/fcm-token")
    suspend fun updateFcmToken(
        @PathVariable userId: String,
        @AuthenticationPrincipal principal: String,
        @Valid @RequestBody request: FcmTokenUpdateRequest
    ): ApiResponse<Unit> {
        if (principal != userId) throw BusinessException(ErrorCode.FORBIDDEN)
        authService.updateFcmToken(userId, request.fcmToken)
        return ApiResponse.ok(message = "FCM 토큰 등록 완료")
    }

    @Operation(summary = "회원 탈퇴", security = [SecurityRequirement(name = "BearerAuth")])
    @DeleteMapping("/{userId}")
    suspend fun deleteUser(
        @PathVariable userId: String,
        @AuthenticationPrincipal principal: String
    ): ApiResponse<Unit> {
        if (principal != userId) throw BusinessException(ErrorCode.FORBIDDEN)
        userService.deleteUser(userId)
        return ApiResponse.ok(message = "회원 탈퇴가 완료되었습니다.")
    }
}
