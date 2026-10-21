package com.onsafe.backend.common.exception

import com.onsafe.backend.common.response.ApiResponse
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.bind.support.WebExchangeBindException
import org.springframework.web.server.MethodNotAllowedException
import org.springframework.web.server.ResponseStatusException
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

    /**
     * Spring이 상태 코드를 정해 던진 예외 — 없는 경로(404)·Content-Type(415) 등.
     * 이 핸들러가 없으면 아래 Exception 핸들러로 가 500 + error 로그가 남았다. 400(ServerWebInputException)·
     * 405(MethodNotAllowedException)도 이 타입의 하위 클래스지만 더 구체적인 위 핸들러가 먼저 받는다.
     * 406(Accept 불일치)은 전용 코드를 두지 않는다 — 클라이언트가 JSON을 받지 않겠다고 해 오류 본문을 쓸 수 없고
     * Spring이 406만 돌려준다.
     * 4xx는 클라이언트 요청 문제이고 외부 스캐너도 두드리므로 로그를 남기지 않는다.
     */
    @ExceptionHandler(ResponseStatusException::class)
    fun handleResponseStatusException(e: ResponseStatusException): ResponseEntity<ApiResponse<Nothing>> {
        val errorCode = when (e.statusCode.value()) {
            HttpStatus.NOT_FOUND.value() -> ErrorCode.API_NOT_FOUND
            HttpStatus.UNSUPPORTED_MEDIA_TYPE.value() -> ErrorCode.UNSUPPORTED_MEDIA_TYPE
            else -> null
        }
        if (errorCode != null) {
            return ResponseEntity
                .status(errorCode.status)
                .body(ApiResponse.fail(errorCode.message, errorCode.name))
        }
        if (e.statusCode.is4xxClientError) {
            return ResponseEntity
                .status(e.statusCode)
                .body(ApiResponse.fail(ErrorCode.INVALID_INPUT.message, ErrorCode.INVALID_INPUT.name))
        }
        return handleException(e)
    }

    @ExceptionHandler(Exception::class)
    fun handleException(e: Exception): ResponseEntity<ApiResponse<Nothing>> {
        log.error("Unhandled exception: ${e::class.simpleName} - ${e.message}", e)
        return ResponseEntity
            .status(ErrorCode.INTERNAL_ERROR.status)
            .body(ApiResponse.fail(ErrorCode.INTERNAL_ERROR.message, ErrorCode.INTERNAL_ERROR.name))
    }
}
