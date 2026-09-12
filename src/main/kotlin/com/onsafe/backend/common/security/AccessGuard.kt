package com.onsafe.backend.common.security

import com.onsafe.backend.common.exception.BusinessException
import com.onsafe.backend.common.exception.ErrorCode
import com.onsafe.backend.domain.guardian.repository.GuardianLinkRepository
import org.springframework.stereotype.Component
import java.time.LocalDateTime

// 보호자/피보호자 관계(GuardianLink)는 유저 고정 role이 아니라 요청 대상 리소스(userId)마다
// 달라지는 M:N 관계다 — Spring Security의 hasRole/hasAuthority/@PreAuthorize는 인증 시점에
// 고정된 role을 전제해 이 구조를 표현할 수 없다(관계 변경이 즉시 반영 안 되고, 한 유저가 여러
// 관계를 가질 수 있어 role 하나로 표현 불가). 그래서 요청마다 대상 리소스 + 호출자 관계를
// 조회하는 이 방식을 표준으로 삼는다. FallLogController가 쓰던 걸 여러 컨트롤러가 공유하도록
// 승격했다.
@Component
class AccessGuard(private val guardianLinkRepository: GuardianLinkRepository) {

    // 접근 승인 결과. 본인은 전체 이력 접근 가능하지만, 보호자는 "연결된 시점 이후"의 이력만
    // 볼 수 있어야 한다(21번째 회의 §3e — 재페어링 시 이전 이력이 새 보호자에게 노출되는 문제).
    // 컨트롤러는 이 반환값의 since 로 쿼리 필터를 걸어 이 정책을 강제한다.
    sealed class Grant {
        // 본인 접근 — 이력 시각 제한 없음
        object Owner : Grant()
        // 보호자 접근 — since(연결 시각) 이후 이력만 접근 허용
        data class Guardian(val since: LocalDateTime) : Grant()
    }

    // 조회성 API 전용 — 본인이거나 연결된 보호자면 통과, Grant 반환. 삭제·업로드처럼 리소스를
    // 직접 조작하는 API 에는 쓰지 않는다(본인 전용으로 남겨야 함).
    // 반환 Grant.since 를 컨트롤러가 하위 쿼리 필터로 전달해야 재페어링 이후에도 이전 이력이
    // 새 보호자에게 노출되지 않는다.
    suspend fun requireOwnerOrGuardian(principal: String, userId: String): Grant {
        if (principal == userId) return Grant.Owner
        val link = guardianLinkRepository.find(principal, userId)
            ?: throw BusinessException(ErrorCode.FORBIDDEN)
        return Grant.Guardian(since = link.createdAt)
    }
}