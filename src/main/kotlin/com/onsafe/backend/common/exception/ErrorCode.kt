package com.onsafe.backend.common.exception

import org.springframework.http.HttpStatus

enum class ErrorCode(
    val status: HttpStatus,
    val message: String
) {
    // ── 공통 ──────────────────────────────────────────────
    FORBIDDEN(HttpStatus.FORBIDDEN, "접근 권한이 없습니다."),
    INVALID_INPUT(HttpStatus.BAD_REQUEST, "요청 값이 유효하지 않습니다."),
    TOO_MANY_REQUESTS(HttpStatus.TOO_MANY_REQUESTS, "요청이 너무 많습니다. 잠시 후 다시 시도해주세요."),
    REDIS_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "일시적으로 요청을 처리할 수 없습니다. 잠시 후 다시 시도해주세요."),

    // ── 인증/회원 ──────────────────────────────────────────
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다."),
    MAIL_ALREADY_EXISTS(HttpStatus.CONFLICT, "이미 사용 중인 이메일입니다."),
    INVALID_PASSWORD(HttpStatus.UNAUTHORIZED, "비밀번호가 일치하지 않습니다."),
    INVALID_TOKEN(HttpStatus.UNAUTHORIZED, "유효하지 않은 토큰입니다."),
    EXPIRED_TOKEN(HttpStatus.UNAUTHORIZED, "만료된 토큰입니다."),

    // ── 사고 이력 ─────────────────────────────────────────
    LOG_NOT_FOUND(HttpStatus.NOT_FOUND, "사고 이력을 찾을 수 없습니다."),
    VIDEO_NOT_FOUND(HttpStatus.NOT_FOUND, "동영상이 존재하지 않습니다."),
    VIDEO_NOT_ALLOWED(HttpStatus.FORBIDDEN, "주의 등급 이벤트는 동영상을 제공하지 않습니다."),

    // ── 카메라 ────────────────────────────────────────────
    REALTIME_DATA_NOT_FOUND(HttpStatus.NOT_FOUND, "실시간 데이터가 없습니다."),

    // ── 아이디 찾기 ───────────────────────────────────────
    USER_ID_ALREADY_EXISTS(HttpStatus.CONFLICT, "이미 사용 중인 아이디입니다."),

    // ── 비밀번호 재설정 ────────────────────────────────────
    MAIL_NOT_MATCH(HttpStatus.BAD_REQUEST, "이메일이 일치하지 않습니다."),
    INVALID_RESET_CODE(HttpStatus.BAD_REQUEST, "유효하지 않은 인증코드입니다. 코드가 만료되었거나 올바르지 않습니다."),

    // ── 이메일 인증 ────────────────────────────────────────
    INVALID_EMAIL_CODE(HttpStatus.BAD_REQUEST, "유효하지 않은 인증코드입니다. 코드가 만료되었거나 올바르지 않습니다."),
    MAIL_SEND_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "이메일 발송에 실패했습니다."),

    // ── 알림 ──────────────────────────────────────────────
    FCM_SEND_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "알림 전송에 실패했습니다."),

    // ── 보호자 페어링 ─────────────────────────────────────
    PAIRING_CODE_INVALID(HttpStatus.BAD_REQUEST, "유효하지 않은 페어링 코드입니다. 코드가 만료되었거나 올바르지 않습니다."),
    SELF_PAIRING_NOT_ALLOWED(HttpStatus.BAD_REQUEST, "본인 계정은 페어링할 수 없습니다."),
    PAIRING_ALREADY_EXISTS(HttpStatus.CONFLICT, "이미 연결된 계정입니다."),
    PAIRING_NOT_FOUND(HttpStatus.NOT_FOUND, "연결된 보호자 관계를 찾을 수 없습니다."),

    // ── 알림 목록 ─────────────────────────────────────────
    NOTIFICATION_NOT_FOUND(HttpStatus.NOT_FOUND, "알림을 찾을 수 없습니다.")
}
