package com.onsafe.backend.domain.consent.service

import com.onsafe.backend.common.exception.BusinessException
import com.onsafe.backend.common.exception.ErrorCode
import com.onsafe.backend.domain.consent.model.dto.ConsentAgreeRequest
import com.onsafe.backend.domain.consent.model.dto.ConsentResponse
import com.onsafe.backend.domain.consent.model.dto.PendingConsentResponse
import com.onsafe.backend.domain.consent.model.entity.CONSENT_POLICIES
import com.onsafe.backend.domain.consent.repository.ConsentRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.time.LocalDateTime

@Service
class ConsentService(
    private val consentRepository: ConsentRepository,
    // 재동의 전 서버 차단 스위치(D3). 프론트 재동의 모달이 배포되기 전에 켜면 동의 기록이 없는
    // 기존 사용자 전원이 막힌다 — 목록 계산·응답은 스위치와 무관하게 항상 동작한다.
    @Value("\${consent.enforcement-enabled:false}") private val enforcementEnabled: Boolean
) {

    // 타입별 최신 동의 이력만 반환한다. 이 기능 도입 이전에 가입한 유저는 레코드가 아예
    // 없어 빈 리스트가 나올 수 있다 — 에러가 아니라 정상적인 레거시 상태로 취급한다.
    suspend fun getConsents(userId: String): List<ConsentResponse> =
        consentRepository.findLatestByUserId(userId).values
            .sortedBy { it.type.ordinal }
            .map { ConsentResponse.from(it) }

    /**
     * 현재 시행 버전에 아직 동의하지 않은 약관 목록(A2).
     * 동의 기록이 아예 없는 레거시 유저는 개정 성격과 무관하게 required — 동의 사실을 증명할 기록 자체가
     * 없기 때문이다. 기록이 있지만 버전이 다르면 그 개정의 requiresReconsent를 따른다(K4).
     */
    suspend fun getPending(userId: String): List<PendingConsentResponse> {
        val latest = consentRepository.findLatestByUserId(userId)
        return CONSENT_POLICIES.entries
            .sortedBy { it.key.ordinal }
            .mapNotNull { (type, policy) ->
                val record = latest[type]
                when {
                    record == null -> PendingConsentResponse(type, policy.version, required = true)
                    record.version != policy.version -> PendingConsentResponse(type, policy.version, policy.requiresReconsent)
                    else -> null
                }
            }
    }

    // access 토큰 `cr` 클레임 값. 스위치가 꺼져 있으면 목록 조회도 하지 않는다(refresh마다 Firestore 조회 방지).
    suspend fun isBlocked(userId: String): Boolean =
        enforcementEnabled && isBlocking(getPending(userId))

    // 이미 목록을 조회한 호출부(로그인)가 같은 조회를 반복하지 않도록 목록으로도 판정한다.
    fun isBlocking(pending: List<PendingConsentResponse>): Boolean =
        enforcementEnabled && pending.any { it.required }

    /**
     * 재동의 기록(A4). 요청 버전이 현재 버전과 다르면 CONSENT_VERSION_MISMATCH — 사용자가 본 적 없는
     * 버전에 동의한 기록을 남기지 않는다. 이미 현재 버전에 동의한 타입은 다시 쓰지 않는다(재시도에 안전).
     * 남은 재동의 목록을 돌려준다 — 비어 있으면 앱은 refresh로 `cr` 없는 토큰을 받는다(D2).
     */
    suspend fun agree(userId: String, request: ConsentAgreeRequest): List<PendingConsentResponse> {
        val items = request.consents.orEmpty()
        val requested = items.associate { it.type!! to it.version!! }
        requested.forEach { (type, version) ->
            if (CONSENT_POLICIES.getValue(type).version != version) {
                throw BusinessException(ErrorCode.CONSENT_VERSION_MISMATCH)
            }
        }

        val pendingTypes = getPending(userId).map { it.type }.toSet()
        consentRepository.appendAll(
            userId = userId,
            versions = requested.filterKeys { it in pendingTypes },
            agreedAt = LocalDateTime.now()
        )
        return getPending(userId)
    }
}
