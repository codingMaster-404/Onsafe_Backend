package com.onsafe.backend.common.exception

import com.onsafe.backend.common.response.ApiResponse
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.bind.support.WebExchangeBindException
import org.springframework.web.server.MethodNotAllowedException
import org.springframework.web.server.ServerWebInputException

@RestControllerAdvice
class GlobalExceptionHandler {

    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(BusinessException::class)
    fun handleBusinessException(e: BusinessException): ResponseEntity<ApiResponse<Nothing>> {
        return ResponseEntity
            .status(e.errorCode.status)
            .body(ApiResponse.fail(e.errorCode.message, e.errorCode.name))
    }

    @ExceptionHandler(WebExchangeBindException::class)
    fun handleValidationException(e: WebExchangeBindException): ResponseEntity<ApiResponse<Nothing>> {
        val message = e.bindingResult.fieldErrors
            .joinToString(", ") { "${it.field}: ${it.defaultMessage}" }
        return ResponseEntity
            .status(ErrorCode.INVALID_INPUT.status)
            .body(ApiResponse.fail(message, ErrorCode.INVALID_INPUT.name))
    }

    /** JSON 역직렬화 실패 (필드명 불일치, 타입 오류 등) → 500 대신 400 반환 */
    @ExceptionHandler(ServerWebInputException::class)
    fun handleServerWebInputException(e: ServerWebInputException): ResponseEntity<ApiResponse<Nothing>> {
        val cause = e.cause?.message ?: e.message ?: "요청 형식이 올바르지 않습니다."
        val message = when {
            cause.contains("Missing required creator property") ->
                "필수 필드가 누락되었습니다. 요청 필드명이 snake_case인지 확인해 주세요. (예: user_id, device_id)"
            cause.contains("Cannot deserialize") ->
                "필드 타입이 올바르지 않습니다."
            else -> "요청 형식이 올바르지 않습니다."
        }
        return ResponseEntity
            .status(ErrorCode.INVALID_INPUT.status)
            .body(ApiResponse.fail(message, ErrorCode.INVALID_INPUT.name))
    }

    @ExceptionHandler(MethodNotAllowedException::class)
    fun handleMethodNotAllowed(e: MethodNotAllowedException): ResponseEntity<ApiResponse<Nothing>> {
        return ResponseEntity
            .status(ErrorCode.METHOD_NOT_ALLOWED.status)
            .body(ApiResponse.fail(ErrorCode.METHOD_NOT_ALLOWED.message, ErrorCode.METHOD_NOT_ALLOWED.name))
    }

    @ExceptionHandler(Exception::class)
    fun handleException(e: Exception): ResponseEntity<ApiResponse<Nothing>> {
        log.error("Unhandled exception: ${e::class.simpleName} - ${e.message}", e)
        return ResponseEntity
            .status(ErrorCode.INTERNAL_ERROR.status)
            .body(ApiResponse.fail(ErrorCode.INTERNAL_ERROR.message, ErrorCode.INTERNAL_ERROR.name))
    }
}
