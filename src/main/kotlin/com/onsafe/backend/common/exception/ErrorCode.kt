package com.onsafe.backend.common.exception

import org.springframework.http.HttpStatus

enum class ErrorCode(
    val status: HttpStatus,
    val message: String
) {
    // ── 공통 ──────────────────────────────────────────────
    FORBIDDEN(HttpStatus.FORBIDDEN, "접근 권한이 없습니다."),
    INVALID_INPUT(HttpStatus.BAD_REQUEST, "요청 값이 유효하지 않습니다."),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "지원하지 않는 HTTP 메서드입니다."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 내부 오류가 발생했습니다."),
    TOO_MANY_REQUESTS(HttpStatus.TOO_MANY_REQUESTS, "요청이 너무 많습니다. 잠시 후 다시 시도해주세요."),
    REDIS_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "일시적으로 요청을 처리할 수 없습니다. 잠시 후 다시 시도해주세요."),

    // ── 인증/회원 ──────────────────────────────────────────
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다."),
    MAIL_ALREADY_EXISTS(HttpStatus.CONFLICT, "이미 사용 중인 이메일입니다."),
    PHONE_ALREADY_EXISTS(HttpStatus.CONFLICT, "이미 사용 중인 전화번호입니다."),
    // 로그인한 사용자가 현재 비밀번호를 틀린 경우(verify-password·개인정보 수정)다. 토큰 문제가 아니므로
    // 401이면 앱이 토큰 만료로 보고 재발급 후 틀린 비밀번호를 한 번 더 보낸다 → 400으로 둔다(B7).
    INVALID_PASSWORD(HttpStatus.BAD_REQUEST, "비밀번호가 일치하지 않습니다."),
    // 개인정보 수정·탈퇴에는 `verify-password`가 발급한 재인증 티켓이 필요하다(B1).
    // 401이 아닌 400으로 둔다 — 앱이 401을 토큰 만료로 보고 재발급을 시도하는 문제(B7)와 같다.
    REAUTH_REQUIRED(HttpStatus.BAD_REQUEST, "본인 확인이 필요합니다. 비밀번호를 다시 확인해주세요."),

    // 로그인 실패는 공개 경로라 401을 유지한다. 아이디 존재 여부를 흘리지 않도록 사유를 뭉뚱그린다(B3).
    LOGIN_FAILED(HttpStatus.UNAUTHORIZED, "아이디 또는 비밀번호가 일치하지 않습니다."),
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
    // 없는 아이디·이름 불일치·메일 불일치를 하나로 돌려준다 — 구분하면 응답만으로 가입 여부가 드러난다.
    RESET_IDENTITY_MISMATCH(HttpStatus.BAD_REQUEST, "입력한 정보와 일치하는 계정을 찾을 수 없습니다."),
    INVALID_RESET_TICKET(HttpStatus.BAD_REQUEST, "재설정 가능 시간이 지났거나 유효하지 않은 요청입니다. 본인확인부터 다시 진행해주세요."),

    // ── 알림 ──────────────────────────────────────────────
    FCM_SEND_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "알림 전송에 실패했습니다."),

    // ── 보호자 페어링 ─────────────────────────────────────
    PAIRING_CODE_INVALID(HttpStatus.BAD_REQUEST, "유효하지 않은 페어링 코드입니다. 코드가 만료되었거나 올바르지 않습니다."),
    SELF_PAIRING_NOT_ALLOWED(HttpStatus.BAD_REQUEST, "본인 계정은 페어링할 수 없습니다."),
    PAIRING_ALREADY_EXISTS(HttpStatus.CONFLICT, "이미 연결된 계정입니다."),
    GUARDIAN_ALREADY_HAS_WARD(HttpStatus.CONFLICT, "이미 다른 피보호자와 연결되어 있습니다. 기존 연결을 해제한 뒤 다시 시도해주세요."),
    ELDER_ALREADY_HAS_GUARDIAN(HttpStatus.CONFLICT, "이미 다른 보호자와 연결되어 있습니다. 기존 연결을 해제한 뒤 다시 시도해주세요."),
    // 역할 배타성 위반 — 한 계정은 보호자 또는 피보호자 중 하나로만 활동할 수 있음
    ROLE_CONFLICT_ALREADY_ELDER(HttpStatus.CONFLICT, "이미 피보호자로 연결된 계정입니다. 보호자 역할로 사용할 수 없습니다."),
    ROLE_CONFLICT_ALREADY_GUARDIAN(HttpStatus.CONFLICT, "이미 보호자로 연결된 계정입니다. 피보호자 역할로 사용할 수 없습니다."),
    PAIRING_REQUEST_INVALID(HttpStatus.BAD_REQUEST, "유효하지 않은 페어링 요청입니다. 요청이 만료되었거나 이미 처리되었습니다."),
    PAIRING_NOT_FOUND(HttpStatus.NOT_FOUND, "연결된 보호자 관계를 찾을 수 없습니다."),

    // ── 알림 목록 ─────────────────────────────────────────
    NOTIFICATION_NOT_FOUND(HttpStatus.NOT_FOUND, "알림을 찾을 수 없습니다."),

    // ── 약관 동의 ─────────────────────────────────────────
    // 토큰은 유효하므로 401이 아니라 403 — 401이면 앱이 refresh를 시도하고, 같은 클레임의 토큰을 다시 받는다.
    CONSENT_REQUIRED(HttpStatus.FORBIDDEN, "개정된 필수 약관에 동의해야 서비스를 이용할 수 있습니다."),
    CONSENT_VERSION_MISMATCH(HttpStatus.CONFLICT, "약관이 다시 개정되었습니다. 최신 약관을 확인한 뒤 다시 동의해주세요."),

    // ── 실시간 영상(LIVE) ─────────────────────────────────
    // LiveKit 설정 누락·토큰 생성 실패 — 다른 기능은 정상이므로 503으로 LIVE만 거절한다.
    LIVE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "실시간 영상 서비스를 일시적으로 사용할 수 없습니다."),
    // 피보호자가 영상 송출에 동의하지 않았다(설정 live_video_enabled=false) — 연결된 보호자여도 볼 수 없다.
    LIVE_NOT_ALLOWED(HttpStatus.FORBIDDEN, "피보호자가 실시간 영상 송출에 동의하지 않았습니다."),
    LIVE_SESSION_NOT_FOUND(HttpStatus.NOT_FOUND, "진행 중인 실시간 영상 요청이 없습니다."),
    // 피보호자 카메라 heartbeat가 오프라인 기준(6분)보다 오래됐다 — 송출 요청을 보내도 받을 기기가 없다(W7).
    LIVE_DEVICE_OFFLINE(HttpStatus.CONFLICT, "피보호자 카메라가 연결되어 있지 않습니다. 카메라 앱이 켜져 있는지 확인해주세요.")
}
