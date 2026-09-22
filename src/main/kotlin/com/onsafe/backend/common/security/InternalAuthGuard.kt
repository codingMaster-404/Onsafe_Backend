package com.onsafe.backend.common.security

import com.onsafe.backend.common.exception.BusinessException
import com.onsafe.backend.common.exception.ErrorCode
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.security.MessageDigest

/**
 * `internal` 하위 경로 호출자 검증 — `X-Internal-Auth` 헤더의 시크릿을 constant-time 비교한다.
 *
 * 이 경로는 `SecurityPaths.PUBLIC_PATTERNS`에 있어 JWT 필터를 타지 않고, Kotlin 서비스는
 * `--allow-unauthenticated`로 배포돼 인터넷에서 직접 호출할 수 있다. 헤더 검증이 없으면
 * `POST /internal/fall-log`로 **가짜 낙상 알림을 보호자에게 보내거나**, `/internal/realtime`으로
 * 위험 점수를 0으로 덮어써 **실제 위험을 가릴 수 있다** — userId만 알면 된다.
 *
 * 시크릿(Secret Manager `INTERNAL_JOB_SECRET`)이 비어 있으면 항상 거부한다(fail-closed).
 * 로컬에서 실수로 포트가 열려도 엔드포인트는 닫힌 상태를 유지한다.
 *
 * Cloud Scheduler 잡(InternalJobController)과 Python AI 서버(InternalController)가 같은 규칙을
 * 쓰도록 한 곳에 둔다 — 각자 구현하면 한쪽만 검증이 빠지는 지금 같은 상황이 다시 생긴다.
 */
@Component
class InternalAuthGuard(
    @Value("\${internal.job.secret:}") private val expectedSecret: String
) {
    fun require(header: String?) {
        if (expectedSecret.isBlank() || header.isNullOrBlank()) {
            throw BusinessException(ErrorCode.FORBIDDEN)
        }
        // 문자열 == 는 첫 불일치 지점에서 조기 반환해 타이밍 공격 여지가 있어 constant-time 비교.
        val expected = expectedSecret.toByteArray(Charsets.UTF_8)
        val actual = header.toByteArray(Charsets.UTF_8)
        if (!MessageDigest.isEqual(expected, actual)) throw BusinessException(ErrorCode.FORBIDDEN)
    }
}
