package com.onsafe.backend.domain.live

import com.onsafe.backend.common.livekit.LiveKitRoomService
import com.onsafe.backend.domain.live.model.entity.LiveSession
import com.onsafe.backend.domain.live.repository.LiveSessionRepository
import com.onsafe.backend.domain.live.service.LiveSweepJob
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Instant

/** 1분 정리 잡(W6) — Redis 세션이 없는(만료된) `live-*` 방만 지우고, 확인하지 못한 방은 건드리지 않는다. */
class LiveSweepJobTest {

    private val roomService: LiveKitRoomService = mockk()
    private val repository: LiveSessionRepository = mockk()
    private val job = LiveSweepJob(roomService, repository)

    @Test
    fun `세션이 없는 live 방만 지우고 진행 중인 방과 다른 방은 둔다`() = runTest {
        coEvery { roomService.listRoomNames() } returns listOf("live-expired", "live-active", "other-room")
        coEvery { repository.find("expired") } returns null
        coEvery { repository.find("active") } returns LiveSession("active", "g1", Instant.now().plusSeconds(60))
        coEvery { roomService.deleteRoom(any()) } returns true

        job.run()

        coVerify(exactly = 1) { roomService.deleteRoom("live-expired") }
        coVerify(exactly = 0) { roomService.deleteRoom("live-active") }
        coVerify(exactly = 0) { roomService.deleteRoom("other-room") }
    }

    @Test
    fun `Redis 확인에 실패한 방은 지우지 않고 다음 방을 계속 본다`() = runTest {
        coEvery { roomService.listRoomNames() } returns listOf("live-a", "live-b")
        coEvery { repository.find("a") } throws RuntimeException("redis down")
        coEvery { repository.find("b") } returns null
        coEvery { roomService.deleteRoom(any()) } returns true

        job.run()

        coVerify(exactly = 0) { roomService.deleteRoom("live-a") }
        coVerify(exactly = 1) { roomService.deleteRoom("live-b") }
    }

    @Test
    fun `방 목록을 못 가져오면 아무것도 하지 않는다`() = runTest {
        coEvery { roomService.listRoomNames() } returns null

        job.run()

        coVerify(exactly = 0) { roomService.deleteRoom(any()) }
    }
}
