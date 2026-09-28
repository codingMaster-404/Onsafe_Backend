package com.onsafe.backend.common.security

import org.springframework.http.HttpMethod
import org.springframework.http.server.reactive.ServerHttpRequest
import org.springframework.web.util.pattern.PathPattern
import org.springframework.web.util.pattern.PathPatternParser

// SecurityConfig의 permitAll 목록과 JwtAuthenticationFilter의 스킵 대상을 단일 소스로 관리.
// 두 곳이 어긋나면 (a) 공개 경로인데 필터가 만료 토큰을 401로 튕겨 로그인 자체가 막히거나
// (b) 필터는 통과했지만 permitAll이 아닌 경로에 미인증 접근이 가능해지는 문제가 생김.
object SecurityPaths {
    val PUBLIC_PATTERNS = arrayOf(
        "/api/auth/**",
        "/swagger-ui/**",
        "/swagger-ui.html",
        "/api-docs/**",
        "/webjars/**",
        "/internal/**",
    )

    private val parser = PathPatternParser()
    private val compiled = PUBLIC_PATTERNS.map { parser.parse(it) }

    // 개정 약관 미동의(`cr` 클레임) 토큰으로도 호출할 수 있는 경로(D4). 동의하거나, 동의하지 않고
    // 탈퇴하거나(재인증 포함), 로그아웃 시 FCM 토큰을 정리하는 경로만 연다. 메서드가 null이면 전부 허용.
    private val CONSENT_EXEMPT: List<Pair<HttpMethod?, PathPattern>> = listOf(
        null to "/api/consents/**",
        HttpMethod.POST to "/api/users/{userId}/verify-password",
        HttpMethod.DELETE to "/api/users/{userId}",
        null to "/api/users/{userId}/fcm-token",
    ).map { (method, pattern) -> method to parser.parse(pattern) }

    fun isPublic(request: ServerHttpRequest): Boolean {
        val path = request.path.pathWithinApplication()
        return compiled.any { it.matches(path) }
    }

    fun isConsentExempt(request: ServerHttpRequest): Boolean {
        val path = request.path.pathWithinApplication()
        return CONSENT_EXEMPT.any { (method, pattern) ->
            (method == null || method == request.method) && pattern.matches(path)
        }
    }
}
