package com.onsafe.backend.domain.guardian

import com.onsafe.backend.common.exception.BusinessException
import com.onsafe.backend.common.exception.ErrorCode
import com.onsafe.backend.domain.guardian.model.entity.GuardianLink
import com.onsafe.backend.domain.guardian.repository.GuardianLinkRepository
import com.onsafe.backend.domain.guardian.service.GuardianService
import com.onsafe.backend.domain.logs.model.entity.FallLog
import com.onsafe.backend.domain.logs.repository.FallLogRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.LocalDateTime

/** 해제 전 확인(S4) — 재알림 대상과 같은 기준(미확인 + 위험 임계 초과, 최근 48시간·연결 이후)으로 센다. */
class GuardianServiceUnpairCheckTest {

    private val guardianLinkRepository: GuardianLinkRepository = mockk()
    private val fallLogRepository: FallLogRepository = mockk()
    private val service = GuardianService(
        guardianLinkRepository, mockk(), mockk(relaxed = true), mockk(), mockk(), mockk(), mockk(relaxed = true),
        mockk(relaxed = true), mockk(relaxed = true), fallLogRepository
    )

    private fun fallLog(id: String, score: Float, confirmed: Boolean = false, fall: Boolean = false) = FallLog(
        logId = id, deviceId = "d", userId = "elder1", score = score, fall = fall, isConfirmed = confirmed
    )

    private fun givenLink(createdAt: LocalDateTime = LocalDateTime.now().minusDays(10)) {
        coEvery { guardianLinkRepository.find("guardian1", "elder1") } returns GuardianLink("guardian1", "elder1", createdAt)
        coEvery { guardianLinkRepository.find("elder1", "guardian1") } returns null
    }

    @Test
    fun `미확인 위험만 센다 - 확인된 것·주의 레벨·점수 낮은 fall은 제외`() = runTest {
        givenLink()
        coEvery { fallLogRepository.findRecentByUserId("elder1", since = any()) } returns listOf(
            fallLog("a", score = 90f),
            fallLog("b", score = 80f),
            fallLog("c", score = 90f, confirmed = true),
            fallLog("d", score = 60f),
            fallLog("e", score = 40f, fall = true),  // 재알림 쿼리는 score만 본다
        )

        assertEquals(2, service.getUnpairCheck("guardian1", "elder1").unconfirmedDangerCount)
    }

    @Test
    fun `피보호자가 호출해도 같은 관계의 피보호자 로그를 센다`() = runTest {
        givenLink()
        coEvery { fallLogRepository.findRecentByUserId("elder1", since = any()) } returns listOf(fallLog("a", score = 90f))

        assertEquals(1, service.getUnpairCheck("elder1", "guardian1").unconfirmedDangerCount)
    }

    @Test
    fun `조회 시작은 48시간 전 - 연결이 더 오래됐을 때`() = runTest {
        givenLink(createdAt = LocalDateTime.now().minusDays(10))
        val since = slot<LocalDateTime>()
        coEvery { fallLogRepository.findRecentByUserId("elder1", since = capture(since)) } returns emptyList()

        service.getUnpairCheck("guardian1", "elder1")

        val hoursAgo = Duration.between(since.captured, LocalDateTime.now()).toHours()
        assertTrue(hoursAgo in 47..48, "since=${since.captured}")
    }

    @Test
    fun `조회 시작은 연결 시각 - 48시간 안에 연결됐을 때(이전 관계 이력 제외)`() = runTest {
        val linkedAt = LocalDateTime.now().minusHours(3)
        givenLink(createdAt = linkedAt)
        coEvery { fallLogRepository.findRecentByUserId("elder1", since = any()) } returns emptyList()

        service.getUnpairCheck("guardian1", "elder1")

        coVerify(exactly = 1) { fallLogRepository.findRecentByUserId("elder1", since = linkedAt) }
    }

    @Test
    fun `연결이 없으면 PAIRING_NOT_FOUND`() = runTest {
        coEvery { guardianLinkRepository.find(any(), any()) } returns null

        val e = runCatching { service.getUnpairCheck("guardian1", "elder1") }.exceptionOrNull() as BusinessException

        assertEquals(ErrorCode.PAIRING_NOT_FOUND, e.errorCode)
    }
}
