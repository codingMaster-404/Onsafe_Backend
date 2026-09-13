package com.onsafe.backend.domain.consent.service

import com.onsafe.backend.domain.consent.model.dto.ConsentResponse
import com.onsafe.backend.domain.consent.repository.ConsentRepository
import org.springframework.stereotype.Service

@Service
class ConsentService(private val consentRepository: ConsentRepository) {

    // 타입별 최신 동의 이력만 반환한다. 이 기능 도입 이전에 가입한 유저는 레코드가 아예
    // 없어 빈 리스트가 나올 수 있다 — 에러가 아니라 정상적인 레거시 상태로 취급한다.
    suspend fun getConsents(userId: String): List<ConsentResponse> =
        consentRepository.findLatestByUserId(userId).values
            .sortedBy { it.type.ordinal }
            .map { ConsentResponse.from(it) }
}
