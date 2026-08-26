package com.onsafe.backend.domain.camera

import com.onsafe.backend.common.exception.BusinessException
import com.onsafe.backend.common.exception.ErrorCode
import com.onsafe.backend.domain.camera.model.entity.RealtimeData
import com.onsafe.backend.domain.camera.repository.RealtimeDataRepository
import com.onsafe.backend.domain.camera.service.CameraService
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class CameraServiceTest {

    private val realtimeDataRepository: RealtimeDataRepository = mockk()
    private lateinit var cameraService: CameraService

    private val baseRealtimeData = RealtimeData(userId = "testUser", score = 30f, level = "정상")

    @BeforeEach
    fun setUp() {
        cameraService = CameraService(realtimeDataRepository)
    }

    // ── 위험도 점수 조회 ──────────────────────────────────────────

    @Test
    fun `위험도 점수 조회 - 실시간 데이터 없으면 REALTIME_DATA_NOT_FOUND 예외 발생`() = runTest {
        coEvery { realtimeDataRepository.findByUserId("testUser") } returns null

        val thrown = runCatching {
            cameraService.getRiskScore("testUser")
        }.exceptionOrNull()

        assertTrue(thrown is BusinessException)
        assertEquals(ErrorCode.REALTIME_DATA_NOT_FOUND, (thrown as BusinessException).errorCode)
    }

    // ── 위험도 상태 조회 ──────────────────────────────────────────

    @Test
    fun `위험도 상태 조회 - 실시간 데이터 없으면 REALTIME_DATA_NOT_FOUND 예외 발생`() = runTest {
        coEvery { realtimeDataRepository.findByUserId("testUser") } returns null

        val thrown = runCatching {
            cameraService.getRiskStatus("testUser")
        }.exceptionOrNull()

        assertTrue(thrown is BusinessException)
        assertEquals(ErrorCode.REALTIME_DATA_NOT_FOUND, (thrown as BusinessException).errorCode)
    }
}
