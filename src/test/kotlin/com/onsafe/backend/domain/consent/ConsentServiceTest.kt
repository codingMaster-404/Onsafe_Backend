package com.onsafe.backend.domain.consent

import com.onsafe.backend.common.exception.BusinessException
import com.onsafe.backend.common.exception.ErrorCode
import com.onsafe.backend.domain.consent.model.dto.ConsentAgreeItem
import com.onsafe.backend.domain.consent.model.dto.ConsentAgreeRequest
import com.onsafe.backend.domain.consent.model.entity.CONSENT_POLICIES
import com.onsafe.backend.domain.consent.model.entity.ConsentRecord
import com.onsafe.backend.domain.consent.model.entity.ConsentType
import com.onsafe.backend.domain.consent.repository.ConsentRepository
import com.onsafe.backend.domain.consent.service.ConsentService
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

class ConsentServiceTest {

    private val consentRepository: ConsentRepository = mockk()
    private val enforced = ConsentService(consentRepository, enforcementEnabled = true)
    private val notEnforced = ConsentService(consentRepository, enforcementEnabled = false)

    private fun current(type: ConsentType) = CONSENT_POLICIES.getValue(type).version

    private fun record(type: ConsentType, version: String) =
        ConsentRecord(userId = "u1", type = type, version = version, agreedAt = LocalDateTime.now())

    private fun allCurrent() = ConsentType.entries.associateWith { record(it, current(it)) }

    @Test
    fun `모든 약관이 현재 버전이면 재동의 목록이 비어 있고 차단하지 않는다`() = runTest {
        coEvery { consentRepository.findLatestByUserId("u1") } returns allCurrent()

        assertTrue(enforced.getPending("u1").isEmpty())
        assertFalse(enforced.isBlocked("u1"))
    }

    @Test
    fun `동의 기록이 없는 레거시 유저는 3종 모두 required 재동의 대상`() = runTest {
        coEvery { consentRepository.findLatestByUserId("u1") } returns emptyMap()

        val pending = enforced.getPending("u1")

        assertEquals(ConsentType.entries.toList(), pending.map { it.type })
        assertTrue(pending.all { it.required })
        assertTrue(enforced.isBlocked("u1"))
    }

    @Test
    fun `이전 버전에 동의했으면 현재 버전으로 재동의 대상이 된다`() = runTest {
        coEvery { consentRepository.findLatestByUserId("u1") } returns
            allCurrent() + (ConsentType.PRIVACY_POLICY to record(ConsentType.PRIVACY_POLICY, "2025-01-01"))

        val pending = enforced.getPending("u1")

        assertEquals(listOf(ConsentType.PRIVACY_POLICY), pending.map { it.type })
        assertEquals(current(ConsentType.PRIVACY_POLICY), pending.single().version)
    }

    @Test
    fun `차단 스위치가 꺼져 있으면 목록 조회 없이 차단하지 않는다 (D3)`() = runTest {
        assertFalse(notEnforced.isBlocked("u1"))
        coVerify(exactly = 0) { consentRepository.findLatestByUserId(any()) }
    }

    @Test
    fun `차단 스위치가 꺼져 있어도 재동의 목록은 계산된다`() = runTest {
        coEvery { consentRepository.findLatestByUserId("u1") } returns emptyMap()

        val pending = notEnforced.getPending("u1")

        assertEquals(3, pending.size)
        assertFalse(notEnforced.isBlocking(pending))
    }

    @Test
    fun `재동의 - 요청 버전이 현재 버전과 다르면 CONSENT_VERSION_MISMATCH, 아무것도 쓰지 않는다`() = runTest {
        val request = ConsentAgreeRequest(listOf(ConsentAgreeItem(ConsentType.TERMS_OF_SERVICE, "2025-01-01")))

        val thrown = runCatching { enforced.agree("u1", request) }.exceptionOrNull()

        assertEquals(ErrorCode.CONSENT_VERSION_MISMATCH, (thrown as BusinessException).errorCode)
        coVerify(exactly = 0) { consentRepository.appendAll(any(), any(), any()) }
    }

    @Test
    fun `재동의 - 대기 중인 약관만 기록하고 남은 목록을 돌려준다`() = runTest {
        // 첫 조회: 이용약관만 현재 버전 / 기록 후 조회: 전부 현재 버전
        coEvery { consentRepository.findLatestByUserId("u1") } returnsMany listOf(
            mapOf(ConsentType.TERMS_OF_SERVICE to record(ConsentType.TERMS_OF_SERVICE, current(ConsentType.TERMS_OF_SERVICE))),
            allCurrent()
        )
        val written = slot<Map<ConsentType, String>>()
        coEvery { consentRepository.appendAll("u1", capture(written), any()) } just Runs

        val request = ConsentAgreeRequest(ConsentType.entries.map { ConsentAgreeItem(it, current(it)) })
        val remaining = enforced.agree("u1", request)

        // 이미 현재 버전인 이용약관은 다시 쓰지 않는다(재시도에 안전)
        assertEquals(setOf(ConsentType.PRIVACY_POLICY, ConsentType.SENSITIVE_INFO), written.captured.keys)
        assertTrue(remaining.isEmpty())
    }
}
