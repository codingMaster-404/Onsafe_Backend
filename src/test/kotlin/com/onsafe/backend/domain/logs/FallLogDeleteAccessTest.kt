package com.onsafe.backend.domain.logs

import com.fasterxml.jackson.databind.ObjectMapper
import com.onsafe.backend.common.exception.ErrorCode
import com.onsafe.backend.common.exception.GlobalExceptionHandler
import com.onsafe.backend.common.security.AccessGuard
import com.onsafe.backend.common.security.JwtProvider
import com.onsafe.backend.common.security.TokenRevocationStore
import com.onsafe.backend.config.SecurityConfig
import com.onsafe.backend.domain.guardian.model.entity.GuardianLink
import com.onsafe.backend.domain.guardian.repository.GuardianLinkRepository
import com.onsafe.backend.domain.logs.controller.FallLogController
import com.onsafe.backend.domain.logs.service.FallLogService
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.data.redis.core.ReactiveValueOperations
import org.springframework.http.HttpHeaders
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.reactive.config.EnableWebFlux
import reactor.core.publisher.Mono
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.util.function.Supplier

// 사고 이력 삭제 권한(R2) — 실제 SecurityConfig·AccessGuard·컨트롤러로 본인·연결된 보호자·남을 가른다.
class FallLogDeleteAccessTest {

    @Configuration
    @EnableWebFlux
    class WebConfig

    private val jwtProvider = JwtProvider("test-secret-key-32-bytes-long!!!!", Duration.ofHours(1).toMillis(), Duration.ofDays(30).toMillis())
    private val redis: ReactiveStringRedisTemplate = mockk()
    private val valueOps: ReactiveValueOperations<String, String> = mockk()
    private val guardianLinkRepository: GuardianLinkRepository = mockk()
    private val fallLogService: FallLogService = mockk()

    private val linkedAt = LocalDateTime.of(2026, 10, 1, 0, 0)

    private val context = AnnotationConfigApplicationContext().apply {
        registerBean(JwtProvider::class.java, Supplier { jwtProvider })
        registerBean(ReactiveStringRedisTemplate::class.java, Supplier { redis })
        registerBean(ObjectMapper::class.java, Supplier { ObjectMapper() })
        registerBean(TokenRevocationStore::class.java, Supplier { TokenRevocationStore(redis, Duration.ofDays(30).toMillis()) })
        registerBean(FallLogService::class.java, Supplier { fallLogService })
        registerBean(AccessGuard::class.java, Supplier { AccessGuard(guardianLinkRepository) })
        register(WebConfig::class.java, SecurityConfig::class.java, FallLogController::class.java, GlobalExceptionHandler::class.java)
        refresh()
    }

    private val client = WebTestClient.bindToApplicationContext(context).build()

    init {
        every { redis.opsForValue() } returns valueOps
        every { valueOps.multiGet(any()) } returns Mono.just(listOf(null, null))
        coEvery { guardianLinkRepository.find(any(), any()) } returns null
        coEvery { guardianLinkRepository.find("guardian1", "elder1") } returns GuardianLink("guardian1", "elder1", linkedAt)
        coJustRun { fallLogService.deleteLog(any(), any(), any(), any()) }
    }

    @AfterEach
    fun close() = context.close()

    private fun delete(caller: String) = client.delete().uri("/api/fall-logs/elder1/log001")
        .header(HttpHeaders.AUTHORIZATION, "Bearer ${jwtProvider.generateAccessToken(caller, Instant.now())}")
        .exchange()

    @Test
    fun `본인은 이력 시각 제한 없이 삭제한다`() {
        delete("elder1").expectStatus().isOk

        coVerify(exactly = 1) { fallLogService.deleteLog("elder1", "log001", "elder1", null) }
    }

    @Test
    fun `연결된 보호자는 연결 시각 이후 이력만 삭제한다`() {
        delete("guardian1").expectStatus().isOk

        // 예전에는 본인이 아니면 모두 403이라 앱의 보호자 삭제가 전부 실패했다.
        coVerify(exactly = 1) { fallLogService.deleteLog("elder1", "log001", "guardian1", linkedAt) }
    }

    @Test
    fun `연결되지 않은 사람은 403 FORBIDDEN`() {
        delete("stranger")
            .expectStatus().isForbidden
            .expectBody().jsonPath("$.code").isEqualTo(ErrorCode.FORBIDDEN.name)

        coVerify(exactly = 0) { fallLogService.deleteLog(any(), any(), any(), any()) }
    }
}
