package com.onsafe.backend.common.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import java.net.InetSocketAddress

/**
 * C3 — X-Forwarded-For는 왼쪽부터 클라이언트가 위조할 수 있다.
 * 오른쪽에서 trustedProxyCount번째 값만 신뢰하는지 확인한다.
 */
class ClientIpResolverTest {

    private fun exchange(forwardedFor: String?, remote: String = "10.0.0.1"): MockServerWebExchange {
        val request = MockServerHttpRequest.get("/api/auth/login")
            .remoteAddress(InetSocketAddress(remote, 443))
            .apply { if (forwardedFor != null) header("X-Forwarded-For", forwardedFor) }
            .build()
        return MockServerWebExchange.from(request)
    }

    @Test
    fun `프록시 1개 - 헤더의 마지막 값을 쓴다`() {
        val resolver = ClientIpResolver(trustedProxyCount = 1)

        assertEquals("1.2.3.4", resolver.resolve(exchange("1.2.3.4")))
    }

    @Test
    fun `프록시 1개 - 클라이언트가 앞에 위조 값을 넣어도 인프라가 붙인 값을 쓴다`() {
        val resolver = ClientIpResolver(trustedProxyCount = 1)

        // 공격자가 X-Forwarded-For: 9.9.9.9 를 보내면 Cloud Run이 실제 IP를 뒤에 덧붙인다.
        assertEquals("1.2.3.4", resolver.resolve(exchange("9.9.9.9, 1.2.3.4")))
    }

    @Test
    fun `프록시 2개(로드밸런서 경유) - 오른쪽에서 두 번째 값을 쓴다`() {
        val resolver = ClientIpResolver(trustedProxyCount = 2)

        assertEquals("1.2.3.4", resolver.resolve(exchange("9.9.9.9, 1.2.3.4, 130.211.0.1")))
    }

    @Test
    fun `헤더가 신뢰 구간보다 짧으면 위조 값 대신 직접 연결 주소로 물러난다`() {
        val resolver = ClientIpResolver(trustedProxyCount = 2)

        assertEquals("10.0.0.1", resolver.resolve(exchange("9.9.9.9")))
    }

    @Test
    fun `헤더가 없거나 비어 있으면 직접 연결 주소를 쓴다`() {
        val resolver = ClientIpResolver(trustedProxyCount = 1)

        assertEquals("10.0.0.1", resolver.resolve(exchange(null)))
        assertEquals("10.0.0.1", resolver.resolve(exchange("   ,  ")))
    }
}
