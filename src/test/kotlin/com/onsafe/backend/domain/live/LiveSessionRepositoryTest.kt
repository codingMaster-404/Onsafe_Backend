package com.onsafe.backend.domain.live

import com.onsafe.backend.domain.live.model.entity.LiveSession
import com.onsafe.backend.domain.live.repository.LiveSessionRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.data.redis.core.ReactiveValueOperations
import reactor.core.publisher.Mono
import java.time.Duration
import java.time.Instant

/** `live:session:{elder}` 값 형식과 TTL — 끝난 세션이 Redis에 남지 않도록 TTL을 만료 시각에 맞춘다. */
class LiveSessionRepositoryTest {

    private val redis: ReactiveStringRedisTemplate = mockk()
    private val valueOps: ReactiveValueOperations<String, String> = mockk()
    private val repository = LiveSessionRepository(redis).also {
        every { redis.opsForValue() } returns valueOps
    }
    private val now = Instant.parse("2026-10-07T03:00:00Z")

    @Test
    fun `저장 - 값은 시작 보호자와 만료 epoch 초, TTL은 만료까지 남은 시간`() = runTest {
        val expiresAt = now.plusSeconds(300)
        every { valueOps.set(any(), any(), any<Duration>()) } returns Mono.just(true)

        repository.save(LiveSession("elder1", "guardian1", expiresAt), now)

        verify { valueOps.set("live:session:elder1", "guardian1|${expiresAt.epochSecond}", Duration.ofSeconds(300)) }
    }

    @Test
    fun `저장 - 이미 만료된 세션은 쓰지 않는다`() = runTest {
        repository.save(LiveSession("elder1", "guardian1", now.minusSeconds(1)), now)

        verify(exactly = 0) { valueOps.set(any(), any(), any<Duration>()) }
    }

    @Test
    fun `조회 - 저장 형식을 세션으로 복원한다`() = runTest {
        every { valueOps.get("live:session:elder1") } returns Mono.just("guardian1|${now.epochSecond}")

        assertEquals(LiveSession("elder1", "guardian1", now), repository.find("elder1"))
    }

    @Test
    fun `조회 - 값이 없거나 형식이 깨졌으면 세션이 없는 것으로 본다`() = runTest {
        every { valueOps.get("live:session:none") } returns Mono.empty()
        every { valueOps.get("live:session:broken") } returns Mono.just("guardian1-no-separator")

        assertNull(repository.find("none"))
        assertNull(repository.find("broken"))
    }
}
