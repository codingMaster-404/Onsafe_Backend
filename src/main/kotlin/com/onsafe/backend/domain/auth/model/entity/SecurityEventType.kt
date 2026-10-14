package com.onsafe.backend.domain.auth.model.entity

/**
 * `login_history`에 남기는 보안 이벤트 종류(C6).
 *
 * 이전에는 로그인 성공·실패만 기록해, 계정이 침해됐을 때 **"언제 들어와 무엇을 바꿨는지"를
 * 재구성할 수 없었다.** 특히 비밀번호 변경은 계정 탈취의 핵심 단계인데 흔적이 남지 않았다.
 *
 * 컬렉션 이름은 `login_history`를 유지한다 — 개명하면 탈퇴 캐스케이드·정리 잡·인덱스·스펙을
 * 모두 손봐야 하는데 얻는 것이 이름뿐이다(완료 문서 D35).
 */
enum class SecurityEventType {
    LOGIN_SUCCESS,
    LOGIN_FAIL,
    /** rate limit(IP·userId)에 막힌 시도 — 자동화 공격의 첫 신호라 성공·실패보다 먼저 드러난다. */
    LOGIN_BLOCKED,
    LOGOUT,
    /** 설정 화면에서 현재 비밀번호를 확인하고 변경 */
    PASSWORD_CHANGE,
    /** 비밀번호 찾기(본인확인 → 재설정 티켓) 경유 변경 */
    PASSWORD_RESET,
    /** 비밀번호 찾기 본인확인 실패·rate limit 차단 — 이름·메일 대입 시도를 사후에 추적하기 위해 남긴다. */
    PASSWORD_RESET_IDENTITY_FAIL,
    ACCOUNT_DELETED,
    /** 보호자가 실시간 영상 시청 시작(연장 포함) — 피보호자 영상 열람 책임 추적용. 대상은 [LoginHistory.targetUserId]. */
    LIVE_VIEW_START,
    /** 보호자가 실시간 영상 시청 종료 */
    LIVE_VIEW_END,
    /**
     * 보호자 연결 성립 — 피보호자가 승인한 시점. 명의는 피보호자, 대상은 보호자.
     * 해제 기록만 있으면 "언제부터 연결돼 있었는지"를 알 수 없어 열람 권한 기간을 재구성할 수 없다.
     */
    PAIRING_CREATED,
    /** 보호자 연결 해제 — 명의는 해제한 사람, 대상은 상대. */
    PAIRING_UNPAIRED,
    /** 재페어링 승인으로 기존 관계가 자동 해제됨 — 명의는 승인한 피보호자, 대상은 밀려난 상대(밀려난 관계마다 1건). */
    PAIRING_DISPLACED,
}
