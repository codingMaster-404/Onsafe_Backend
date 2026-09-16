package com.onsafe.backend.common.response

/**
 * 모든 API 응답에 사용되는 공통 래퍼
 * @param success 요청 성공 여부
 * @param message 응답 메시지
 * @param data 실제 응답 데이터 (실패 시 null)
 * @param code 실패 사유 코드([com.onsafe.backend.common.exception.ErrorCode] 이름). 성공 응답은 항상 null.
 *   메시지 문구는 바뀔 수 있어 클라이언트가 분기 기준으로 쓸 수 없고, 상태코드만으로는
 *   같은 401 안에서 토큰 만료와 비밀번호 불일치를 구분할 수 없다(B7).
 */
data class ApiResponse<T>(
    val success: Boolean,
    val message: String,
    val data: T? = null,
    val code: String? = null
) {
    companion object {
        fun <T> ok(data: T, message: String = "요청이 성공했습니다.") =
            ApiResponse(success = true, message = message, data = data)

        fun <T> ok(message: String = "요청이 성공했습니다.") =
            ApiResponse<T>(success = true, message = message)

        fun <T> fail(message: String, code: String? = null) =
            ApiResponse<T>(success = false, message = message, code = code)
    }
}
