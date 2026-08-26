package com.onsafe.backend.domain.auth.controller

import com.onsafe.backend.common.response.ApiResponse
import com.onsafe.backend.common.util.clientIpAddress
import com.onsafe.backend.domain.auth.model.dto.*
import com.onsafe.backend.domain.auth.service.AuthService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ServerWebExchange

@Tag(name = "Auth", description = "인증 API")
@RestController
@RequestMapping("/api/auth")
class AuthController(private val authService: AuthService) {

    @Operation(summary = "회원가입")
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun register(@Valid @RequestBody request: RegisterRequest): ApiResponse<Unit> {
        authService.register(request)
        return ApiResponse.ok(message = "회원가입이 완료되었습니다.")
    }

    @Operation(summary = "로그인")
    @PostMapping("/login")
    suspend fun login(
        @Valid @RequestBody request: LoginRequest,
        exchange: ServerWebExchange
    ): ApiResponse<LoginResponse> {
        val ipAddress = exchange.clientIpAddress()
        val userAgent = exchange.request.headers.getFirst("User-Agent") ?: "unknown"
        val response = authService.login(request, ipAddress, userAgent)
        return ApiResponse.ok(response)
    }

    @Operation(summary = "로그아웃")
    @PostMapping("/logout")
    suspend fun logout(
        @RequestHeader(value = "Authorization", required = false) authorization: String?,
        @RequestHeader(value = "Refresh-Token", required = false) refreshToken: String?
    ): ApiResponse<Unit> {
        val accessToken = authorization?.removePrefix("Bearer ")
        authService.logout(accessToken, refreshToken)
        return ApiResponse.ok(message = "로그아웃 완료")
    }

    @Operation(summary = "아이디 찾기")
    @PostMapping("/find-id")
    suspend fun findId(@Valid @RequestBody request: FindIdRequest): ApiResponse<FindIdResponse> {
        val response = authService.findId(request)
        return ApiResponse.ok(response)
    }

    @Operation(summary = "비밀번호 재설정")
    @PostMapping("/reset-password")
    suspend fun resetPassword(@Valid @RequestBody request: ResetPasswordRequest): ApiResponse<Unit> {
        authService.resetPassword(request)
        return ApiResponse.ok(message = "비밀번호가 변경되었습니다")
    }

    @Operation(summary = "아이디 중복확인")
    @PostMapping("/check-id")
    suspend fun checkId(@Valid @RequestBody request: CheckIdRequest): ApiResponse<Unit> {
        authService.checkId(request)
        return ApiResponse.ok(message = "사용 가능한 아이디입니다.")
    }

    @Operation(summary = "회원가입 이메일 인증코드 발송")
    @PostMapping("/send-email-code")
    suspend fun sendEmailCode(@Valid @RequestBody request: SendEmailCodeRequest): ApiResponse<Unit> {
        authService.sendEmailCode(request)
        return ApiResponse.ok(message = "인증코드가 발송되었습니다.")
    }

    @Operation(summary = "회원가입 이메일 인증코드 확인")
    @PostMapping("/verify-email-code")
    suspend fun verifyEmailCode(@Valid @RequestBody request: VerifyEmailCodeRequest): ApiResponse<Unit> {
        authService.verifyEmailCode(request)
        return ApiResponse.ok(message = "이메일 인증이 완료되었습니다.")
    }

    @Operation(summary = "비밀번호 재설정 인증코드 발송")
    @PostMapping("/send-reset-code")
    suspend fun sendResetCode(@Valid @RequestBody request: SendResetCodeRequest): ApiResponse<Unit> {
        authService.sendResetCode(request)
        return ApiResponse.ok(message = "인증코드가 발송되었습니다.")
    }

    @Operation(summary = "비밀번호 재설정 인증코드 확인")
    @PostMapping("/verify-reset-code")
    suspend fun verifyResetCode(@Valid @RequestBody request: VerifyResetCodeRequest): ApiResponse<Unit> {
        authService.verifyResetCode(request)
        return ApiResponse.ok(message = "인증코드가 확인되었습니다.")
    }

    @Operation(summary = "토큰 재발급")
    @PostMapping("/refresh")
    suspend fun refresh(@RequestHeader("Refresh-Token") refreshToken: String): ApiResponse<TokenResponse> {
        val tokens = authService.refresh(refreshToken)
        return ApiResponse.ok(tokens)
    }
}
