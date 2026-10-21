package com.onsafe.backend.common.exception

import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

// Spring이 던지는 상태 코드 예외(없는 경로·Content-Type·Accept)가 500으로 바뀌지 않는지 확인한다.
class GlobalExceptionHandlerTest {

    @RestController
    class TestController {
        @PostMapping("/test/json", consumes = [MediaType.APPLICATION_JSON_VALUE])
        fun json(@RequestBody body: Map<String, Any>) = body

        @GetMapping("/test/json-only", produces = [MediaType.APPLICATION_JSON_VALUE])
        fun jsonOnly() = mapOf("ok" to true)

        @GetMapping("/test/business")
        fun business(): Nothing = throw BusinessException(ErrorCode.LOG_NOT_FOUND)

        @GetMapping("/test/conflict")
        fun conflict(): Nothing = throw ResponseStatusException(HttpStatus.CONFLICT)

        @GetMapping("/test/unavailable")
        fun unavailable(): Nothing = throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE)

        @GetMapping("/test/boom")
        fun boom(): Nothing = throw IllegalStateException("bug")
    }

    private val client = WebTestClient
        .bindToController(TestController())
        .controllerAdvice(GlobalExceptionHandler())
        .build()

    private fun WebTestClient.ResponseSpec.expectFail(status: HttpStatus, code: ErrorCode) {
        expectStatus().isEqualTo(status)
            .expectBody()
            .jsonPath("$.success").isEqualTo(false)
            .jsonPath("$.code").isEqualTo(code.name)
            .jsonPath("$.message").isEqualTo(code.message)
    }

    @Test
    fun `없는 경로는 404 API_NOT_FOUND`() {
        client.get().uri("/api/no-such-path").exchange()
            .expectFail(HttpStatus.NOT_FOUND, ErrorCode.API_NOT_FOUND)
    }

    @Test
    fun `지원하지 않는 Content-Type은 415 UNSUPPORTED_MEDIA_TYPE`() {
        client.post().uri("/test/json")
            .contentType(MediaType.TEXT_PLAIN)
            .bodyValue("hello")
            .exchange()
            .expectFail(HttpStatus.UNSUPPORTED_MEDIA_TYPE, ErrorCode.UNSUPPORTED_MEDIA_TYPE)
    }

    @Test
    fun `제공할 수 없는 Accept는 406`() {
        // 클라이언트가 JSON을 받지 않겠다고 했으므로 오류 본문(JSON)도 쓸 수 없다 — 상태 코드만 확인한다
        client.get().uri("/test/json-only")
            .accept(MediaType.APPLICATION_XML)
            .exchange()
            .expectStatus().isEqualTo(HttpStatus.NOT_ACCEPTABLE)
    }

    @Test
    fun `그 밖의 4xx는 상태 코드 그대로 INVALID_INPUT`() {
        client.get().uri("/test/conflict").exchange()
            .expectFail(HttpStatus.CONFLICT, ErrorCode.INVALID_INPUT)
    }

    @Test
    fun `5xx 상태 예외는 지금처럼 500 INTERNAL_ERROR`() {
        client.get().uri("/test/unavailable").exchange()
            .expectFail(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR)
    }

    // ── 기존 동작 유지 ──────────────────────────────────────────

    @Test
    fun `지원하지 않는 메서드는 그대로 405 METHOD_NOT_ALLOWED`() {
        client.delete().uri("/test/json-only").exchange()
            .expectFail(HttpStatus.METHOD_NOT_ALLOWED, ErrorCode.METHOD_NOT_ALLOWED)
    }

    @Test
    fun `깨진 JSON은 그대로 400 INVALID_INPUT`() {
        client.post().uri("/test/json")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{not json")
            .exchange()
            .expectStatus().isBadRequest
            .expectBody()
            .jsonPath("$.code").isEqualTo(ErrorCode.INVALID_INPUT.name)
    }

    @Test
    fun `BusinessException 404는 그대로 자기 코드`() {
        client.get().uri("/test/business").exchange()
            .expectFail(HttpStatus.NOT_FOUND, ErrorCode.LOG_NOT_FOUND)
    }

    @Test
    fun `그 외 예외는 그대로 500 INTERNAL_ERROR`() {
        client.get().uri("/test/boom").exchange()
            .expectFail(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR)
    }
}
