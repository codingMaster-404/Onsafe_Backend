package com.onsafe.backend.common.security

import org.springframework.http.server.reactive.ServerHttpRequest
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

    fun isPublic(request: ServerHttpRequest): Boolean {
        val path = request.path.pathWithinApplication()
        return compiled.any { it.matches(path) }
    }
}