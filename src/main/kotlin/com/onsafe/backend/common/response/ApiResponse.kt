package com.onsafe.backend.common.response

/**
 * 모든 API 응답에 사용되는 공통 래퍼
 * @param success 요청 성공 여부
 * @param message 응답 메시지
 * @param data 실제 응답 데이터 (실패 시 null)
 */
data class ApiResponse<T>(
    val success: Boolean,
    val message: String,
    val data: T? = null
) {
    companion object {
        fun <T> ok(data: T, message: String = "요청이 성공했습니다.") =
            ApiResponse(success = true, message = message, data = data)

        fun <T> ok(message: String = "요청이 성공했습니다.") =
            ApiResponse<T>(success = true, message = message)

        fun <T> fail(message: String) =
            ApiResponse<T>(success = false, message = message)
    }
}
