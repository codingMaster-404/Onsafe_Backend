package com.onsafe.backend.common.security

import com.fasterxml.jackson.databind.ObjectMapper
import com.onsafe.backend.common.exception.ErrorCode
import com.onsafe.backend.common.response.ApiResponse
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.ReactiveSecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono

@Component
class JwtAuthenticationFilter(
    private val jwtProvider: JwtProvider,
    private val redis: ReactiveStringRedisTemplate,
    private val objectMapper: ObjectMapper,
    private val tokenRevocationStore: TokenRevocationStore
) : WebFilter {

    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
        // 공개 경로(로그인/회원가입/refresh 등)는 클라이언트가 실수로 만료 토큰을 첨부해도
        // 401로 튕기지 않도록 필터 검증 자체를 건너뛴다. logout/refresh는 자체 헤더 파싱을 사용한다.
        if (SecurityPaths.isPublic(exchange.request)) return chain.filter(exchange)

        val token = extractToken(exchange) ?: return chain.filter(exchange)

        // refresh 토큰(30일)으로 보호 API에 접근하지 못하도록 access 타입만 받는다.
        val validationError = jwtProvider.getValidationError(token, TokenType.ACCESS)
        if (validationError != null) return writeErrorResponse(exchange, validationError)

        val userId = jwtProvider.getUserId(token)
        val issuedAt = jwtProvider.getIssuedAt(token)

        // 토큰 단위 블랙리스트(로그아웃·재발급)와 사용자 단위 무효화 시각(탈퇴·비밀번호 변경·재설정)을
        // MGET 한 번으로 조회해 요청당 Redis 왕복을 늘리지 않는다. 없는 키는 null로 온다.
        val keys = listOf(jwtProvider.blacklistKey(token), tokenRevocationStore.key(userId))
        return redis.opsForValue().multiGet(keys)
            .flatMap { values ->
                val blacklisted = values[0] != null
                val revoked = tokenRevocationStore.isRevoked(issuedAt, values[1])
                if (blacklisted || revoked) {
                    writeErrorResponse(exchange, ErrorCode.INVALID_TOKEN)
                } else {
                    // 보호자/피보호자 관계는 인증 시점에 고정되는 role로 표현할 수 없는 M:N 구조라
                    // (AccessGuard 참고) Spring Security의 role/authority 기반 인가를 쓰지 않는다.
                    // authorities는 항상 비워두고, 리소스별 인가는 각 컨트롤러가 명시적으로 처리한다.
                    val auth = UsernamePasswordAuthenticationToken(userId, null, emptyList())
                    chain.filter(exchange)
                        .contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth))
                }
            }
    }

    private fun writeErrorResponse(exchange: ServerWebExchange, errorCode: ErrorCode): Mono<Void> {
        val response = exchange.response
        response.statusCode = errorCode.status
        response.headers.contentType = MediaType.APPLICATION_JSON
        val bytes = objectMapper.writeValueAsBytes(ApiResponse.fail<Nothing>(errorCode.message))
        val buffer = response.bufferFactory().wrap(bytes)
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
