package com.onsafe.backend.common.security

import com.fasterxml.jackson.databind.ObjectMapper
import com.onsafe.backend.common.exception.ErrorCode
import com.onsafe.backend.config.SecurityConfig
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.data.redis.core.ReactiveValueOperations
import org.springframework.http.HttpHeaders
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.reactive.config.EnableWebFlux
import reactor.core.publisher.Mono
import java.time.Duration
import java.time.Instant
import java.util.function.Supplier

// 실제 SecurityConfig를 컨텍스트에 올려 JWT 필터가 요청당 한 번만 실행되는지 확인한다(M4).
// bindToApplicationContext는 Spring Boot와 같은 WebHttpHandlerBuilder로 WebFilter 빈을 모두 체인에 넣는다 —
// 필터가 다시 빈(@Component·@Bean)이 되면 일반 체인에도 등록돼 Redis 조회가 2회로 늘어 실패한다.
class JwtFilterRegistrationTest {

    @Configuration
    @EnableWebFlux
    class WebConfig

    @RestController
    class PingController {
        @GetMapping("/api/ping")
        fun ping(@AuthenticationPrincipal principal: String) = mapOf("user" to principal)

        @GetMapping("/api/auth/ping")
        fun publicPing() = mapOf("ok" to true)
    }

    private val jwtProvider = JwtProvider("test-secret-key-32-bytes-long!!!!", Duration.ofHours(1).toMillis(), Duration.ofDays(30).toMillis())
    private val redis: ReactiveStringRedisTemplate = mockk()
    private val valueOps: ReactiveValueOperations<String, String> = mockk()

    private val context = AnnotationConfigApplicationContext().apply {
        registerBean(JwtProvider::class.java, Supplier { jwtProvider })
        registerBean(ReactiveStringRedisTemplate::class.java, Supplier { redis })
        registerBean(ObjectMapper::class.java, Supplier { ObjectMapper() })
        registerBean(TokenRevocationStore::class.java, Supplier { TokenRevocationStore(redis, Duration.ofDays(30).toMillis()) })
        register(WebConfig::class.java, SecurityConfig::class.java, PingController::class.java)
        refresh()
    }

    private val client = WebTestClient.bindToApplicationContext(context).build()

    init {
        every { redis.opsForValue() } returns valueOps
        every { valueOps.multiGet(any()) } returns Mono.just(listOf(null, null))
    }

    @AfterEach
    fun close() = context.close()

    @Test
    fun `보호 API 한 번에 JWT 필터의 Redis 조회는 1회`() {
        val token = jwtProvider.generateAccessToken("testUser", Instant.now())

        client.get().uri("/api/ping")
            .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
            .exchange()
            .expectStatus().isOk
            // 보안 체인 안에서 한 번 실행한 필터의 인증 정보가 컨트롤러까지 전달된다
            .expectBody().jsonPath("$.user").isEqualTo("testUser")

        verify(exactly = 1) { valueOps.multiGet(any()) }
    }

    @Test
    fun `무효화된 토큰은 그대로 401 INVALID_TOKEN`() {
        val token = jwtProvider.generateAccessToken("testUser", Instant.now())
        every { valueOps.multiGet(any()) } returns Mono.just(listOf("1", null))

        client.get().uri("/api/ping")
            .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
            .exchange()
            .expectStatus().isUnauthorized
            .expectBody().jsonPath("$.code").isEqualTo(ErrorCode.INVALID_TOKEN.name)

        verify(exactly = 1) { valueOps.multiGet(any()) }
    }

    @Test
    fun `토큰 없는 보호 API는 그대로 401`() {
        client.get().uri("/api/ping").exchange()
            .expectStatus().isUnauthorized

        verify(exactly = 0) { valueOps.multiGet(any()) }
    }

    @Test
    fun `공개 경로는 토큰이 있어도 검증하지 않는다`() {
        client.get().uri("/api/auth/ping")
            .header(HttpHeaders.AUTHORIZATION, "Bearer expired-or-broken")
            .exchange()
            .expectStatus().isOk

        verify(exactly = 0) { valueOps.multiGet(any()) }
    }
}
