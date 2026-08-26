package com.onsafe.backend.common.security

import com.onsafe.backend.common.exception.ErrorCode
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.ReactiveSecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono

@Component
class JwtAuthenticationFilter(
    private val jwtProvider: JwtProvider,
    private val redis: ReactiveStringRedisTemplate
) : WebFilter {

    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
        // 공개 경로(로그인/회원가입/refresh 등)는 클라이언트가 실수로 만료 토큰을 첨부해도
        // 401로 튕기지 않도록 필터 검증 자체를 건너뛴다. logout/refresh는 자체 헤더 파싱을 사용한다.
        if (SecurityPaths.isPublic(exchange.request)) return chain.filter(exchange)

        val token = extractToken(exchange) ?: return chain.filter(exchange)

        val validationError = jwtProvider.getValidationError(token)
        if (validationError != null) return writeErrorResponse(exchange, validationError)

        val userId = jwtProvider.getUserId(token)

        return redis.opsForValue().get("bl:$token")
            .defaultIfEmpty("")
            .flatMap { blacklisted ->
                if (blacklisted.isNotEmpty()) {
                    writeErrorResponse(exchange, ErrorCode.INVALID_TOKEN)
                } else {
                    val auth = UsernamePasswordAuthenticationToken(
                        userId, null, listOf(SimpleGrantedAuthority("ROLE_USER"))
                    )
                    chain.filter(exchange)
                        .contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth))
                }
            }
    }

    private fun writeErrorResponse(exchange: ServerWebExchange, errorCode: ErrorCode): Mono<Void> {
        val response = exchange.response
        response.statusCode = errorCode.status
        response.headers.contentType = MediaType.APPLICATION_JSON
        val body = """{"success":false,"message":"${errorCode.message}","data":null}"""
        val buffer = response.bufferFactory().wrap(body.toByteArray(Charsets.UTF_8))
        return response.writeWith(Mono.just(buffer))
    }

    // Authorization 헤더 우선, 없으면 ?token= 쿼리 파라미터 (WebSocket 업그레이드 요청용)
    private fun extractToken(exchange: ServerWebExchange): String? {
        val headerToken = exchange.request.headers.getFirst(HttpHeaders.AUTHORIZATION)
            ?.takeIf { it.startsWith("Bearer ") }
            ?.removePrefix("Bearer ")
        if (headerToken != null) return headerToken
        return exchange.request.queryParams.getFirst("token")
    }
}
