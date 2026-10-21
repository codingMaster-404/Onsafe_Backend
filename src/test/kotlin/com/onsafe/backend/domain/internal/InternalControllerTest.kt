package com.onsafe.backend.domain.internal

import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.onsafe.backend.common.exception.GlobalExceptionHandler
import com.onsafe.backend.common.security.InternalAuthGuard
import com.onsafe.backend.domain.internal.controller.InternalController
import com.onsafe.backend.domain.internal.service.InternalService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.http.codec.json.Jackson2JsonDecoder
import org.springframework.http.codec.json.Jackson2JsonEncoder
import org.springframework.test.web.reactive.server.WebTestClient

// /internal/fall-log 응답 모양은 Python(app/domain/camera/service.py)이 읽는 서비스 간 계약이라 테스트로 고정한다.
class InternalControllerTest {

    private val internalService: InternalService = mockk()

    // 운영과 같은 전역 SNAKE_CASE 정책(application.yml spring.jackson.property-naming-strategy)으로 직렬화한다
    private val mapper = jacksonObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)

    private val client = WebTestClient
        .bindToController(InternalController(internalService, InternalAuthGuard(SECRET)))
        .controllerAdvice(GlobalExceptionHandler())
        .httpMessageCodecs {
            it.defaultCodecs().jackson2JsonDecoder(Jackson2JsonDecoder(mapper))
            it.defaultCodecs().jackson2JsonEncoder(Jackson2JsonEncoder(mapper))
        }
        .build()

    private val body = """
        {"log_id":"log001","device_id":"device1","user_id":"elder1","score":90.0,"fall":true,"is_confirmed":false,"video_url":null}
    """.trimIndent()

    private fun post(auth: String? = SECRET) = client.post().uri("/internal/fall-log")
        .contentType(MediaType.APPLICATION_JSON)
        .apply { if (auth != null) header("X-Internal-Auth", auth) }
        .bodyValue(body)
        .exchange()

    @Test
    fun `저장하면 data saved=true와 저장 완료 문구를 돌려준다`() {
        coEvery { internalService.saveFallLog(any()) } returns true

        post().expectStatus().isOk
            .expectBody()
            .jsonPath("$.success").isEqualTo(true)
            .jsonPath("$.data.saved").isEqualTo(true)
            .jsonPath("$.message").isEqualTo("낙상 로그 저장 완료")
    }

    @Test
    fun `저장을 건너뛰면 200 그대로 data saved=false와 건너뜀 문구를 돌려준다`() {
        coEvery { internalService.saveFallLog(any()) } returns false

        post().expectStatus().isOk
            .expectBody()
            .jsonPath("$.success").isEqualTo(true)
            .jsonPath("$.data.saved").isEqualTo(false)
            .jsonPath("$.message").isEqualTo("낙상 로그 저장 건너뜀 (계정 없음 또는 연결된 보호자 없음)")
    }

    @Test
    fun `인증 헤더가 없으면 지금처럼 403이고 저장을 시도하지 않는다`() {
        post(auth = null).expectStatus().isForbidden
            .expectBody()
            .jsonPath("$.code").isEqualTo("FORBIDDEN")

        coVerify(exactly = 0) { internalService.saveFallLog(any()) }
    }

    private companion object {
        const val SECRET = "test-internal-secret"
    }
}
