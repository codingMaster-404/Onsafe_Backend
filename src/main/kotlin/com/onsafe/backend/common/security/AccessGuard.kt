package com.onsafe.backend.common.security

import com.onsafe.backend.common.exception.BusinessException
import com.onsafe.backend.common.exception.ErrorCode
import com.onsafe.backend.domain.guardian.repository.GuardianLinkRepository
import org.springframework.stereotype.Component

// 보호자/피보호자 관계(GuardianLink)는 유저 고정 role이 아니라 요청 대상 리소스(userId)마다
// 달라지는 M:N 관계다 — Spring Security의 hasRole/hasAuthority/@PreAuthorize는 인증 시점에
// 고정된 role을 전제해 이 구조를 표현할 수 없다(관계 변경이 즉시 반영 안 되고, 한 유저가 여러
// 관계를 가질 수 있어 role 하나로 표현 불가). 그래서 요청마다 대상 리소스 + 호출자 관계를
// 조회하는 이 방식을 표준으로 삼는다. FallLogController가 쓰던 걸 여러 컨트롤러가 공유하도록
// 승격했다.
@Component
class AccessGuard(private val guardianLinkRepository: GuardianLinkRepository) {

    // 조회성 API 전용 — 본인이거나 연결된 보호자면 통과. 삭제·업로드처럼 리소스를 직접
    // 조작하는 API에는 쓰지 않는다(본인 전용으로 남겨야 함, FallLogController 예시 참고).
    suspend fun requireOwnerOrGuardian(principal: String, userId: String) {
        if (principal == userId) return
        if (guardianLinkRepository.exists(principal, userId)) return
        throw BusinessException(ErrorCode.FORBIDDEN)
    }
}
