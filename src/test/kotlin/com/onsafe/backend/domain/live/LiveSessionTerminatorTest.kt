package com.onsafe.backend.domain.live

import com.onsafe.backend.common.livekit.LiveKitRoomService
import com.onsafe.backend.domain.live.repository.LiveSessionRepository
import com.onsafe.backend.domain.live.service.LiveSessionTerminator
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** 강제 종료는 호출부(연결 해제·탈퇴 등)를 막지 않아야 한다 — 어떤 실패도 밖으로 던지지 않는다. */
class LiveSessionTerminatorTest {

    private val repository: LiveSessionRepository = mockk()
    private val roomService: LiveKitRoomService = mockk()
    private val terminator = LiveSessionTerminator(repository, roomService)

    @Test
    fun `세션과 방을 모두 지우고 세션이 있었으면 true`() = runTest {
        coEvery { repository.delete("elder1") } returns true
        coEvery { roomService.deleteRoom("live-elder1") } returns true

        assertTrue(terminator.terminate("elder1", "unpaired"))
        coVerify(exactly = 1) { roomService.deleteRoom("live-elder1") }
    }

    @Test
    fun `세션 키가 없어도 방은 지운다 (만료 직후 남은 방)`() = runTest {
        coEvery { repository.delete("elder1") } returns false
        coEvery { roomService.deleteRoom("live-elder1") } returns true

        assertFalse(terminator.terminate("elder1", "unpaired"))
        coVerify(exactly = 1) { roomService.deleteRoom("live-elder1") }
    }

    @Test
    fun `Redis·LiveKit이 실패해도 예외를 던지지 않는다`() = runTest {
        coEvery { repository.delete("elder1") } throws RuntimeException("redis down")
        coEvery { roomService.deleteRoom("live-elder1") } throws RuntimeException("livekit down")

        assertFalse(terminator.terminate("elder1", "account_deleted"))
    }
}
