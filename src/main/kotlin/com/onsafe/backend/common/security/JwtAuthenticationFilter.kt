package com.onsafe.backend.common.security

import com.fasterxml.jackson.databind.ObjectMapper
import com.onsafe.backend.common.exception.ErrorCode
import com.onsafe.backend.common.response.ApiResponse
import org.slf4j.LoggerFactory
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

private const val BEARER_PREFIX = "Bearer "

// Redis 조회 결과 — 거부(401)와 "확인 불가"(503)를 나눠야 장애를 토큰 문제로 오인하지 않는다.
private enum class TokenStatus { VALID, REJECTED, UNKNOWN }

@Component
class JwtAuthenticationFilter(
    private val jwtProvider: JwtProvider,
    private val redis: ReactiveStringRedisTemplate,
    private val objectMapper: ObjectMapper,
    private val tokenRevocationStore: TokenRevocationStore
) : WebFilter {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
        // 공개 경로(로그인/회원가입/refresh 등)는 클라이언트가 실수로 만료 토큰을 첨부해도
        // 401로 튕기지 않도록 필터 검증 자체를 건너뛴다. logout/refresh는 자체 헤더 파싱을 사용한다.
        if (SecurityPaths.isPublic(exchange.request)) return chain.filter(exchange)

        val token = extractToken(exchange) ?: return chain.filter(exchange)

        // refresh 토큰(30일)으로 보호 API에 접근하지 못하도록 access 타입만 받는다.
        // 검증과 클레임 조회를 파싱 1회로 처리한다(C7 ③) — 이전에는 요청마다 3번 파싱했다.
        val parsed = when (val result = jwtProvider.parse(token, TokenType.ACCESS)) {
            is TokenParseResult.Invalid -> return writeErrorResponse(exchange, result.errorCode)
            is TokenParseResult.Valid -> result
        }

        // 토큰 단위 블랙리스트(로그아웃·재발급)와 사용자 단위 무효화 시각(탈퇴·비밀번호 변경·재설정)을
        // MGET 한 번으로 조회해 요청당 Redis 왕복을 늘리지 않는다. 없는 키는 null로 온다.
        val keys = listOf(jwtProvider.blacklistKey(token), tokenRevocationStore.key(parsed.userId))
        val status = redis.opsForValue().multiGet(keys)
            .map { values ->
                val blacklisted = values[0] != null
                val revoked = tokenRevocationStore.isRevoked(parsed.issuedAt, values[1])
                if (blacklisted || revoked) TokenStatus.REJECTED else TokenStatus.VALID
            }
            // Redis 장애로 확인할 수 없는 토큰은 통과시키지 않는다(fail-closed). 500 대신 503으로
            // 내려야 클라이언트가 "토큰 문제"가 아니라 일시 장애로 보고 재시도한다(C7 ①).
            // onErrorResume을 체인 뒤에 걸면 컨트롤러 예외까지 503이 되므로 Redis 조회에만 건다.
            .onErrorResume { e ->
                log.error("Redis 오류 (토큰 블랙리스트·무효화 조회): ${e.message}", e)
                Mono.just(TokenStatus.UNKNOWN)
            }

        return status.flatMap { result ->
            when (result) {
                TokenStatus.REJECTED -> writeErrorResponse(exchange, ErrorCode.INVALID_TOKEN)
                TokenStatus.UNKNOWN -> writeErrorResponse(exchange, ErrorCode.REDIS_UNAVAILABLE)
                // 보호자/피보호자 관계는 인증 시점에 고정되는 role로 표현할 수 없는 M:N 구조라
                // (AccessGuard 참고) Spring Security의 role/authority 기반 인가를 쓰지 않는다.
                // authorities는 항상 비워두고, 리소스별 인가는 각 컨트롤러가 명시적으로 처리한다.
                TokenStatus.VALID -> {
                    val auth = UsernamePasswordAuthenticationToken(parsed.userId, null, emptyList())
                    chain.filter(exchange)
                        .contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth))
                }
            }
        }
    }

    private fun writeErrorResponse(exchange: ServerWebExchange, errorCode: ErrorCode): Mono<Void> {
        val response = exchange.response
        response.statusCode = errorCode.status
        response.headers.contentType = MediaType.APPLICATION_JSON
        val bytes = objectMapper.writeValueAsBytes(ApiResponse.fail<Nothing>(errorCode.message, errorCode.name))
        val buffer = response.bufferFactory().wrap(bytes)
        return response.writeWith(Mono.just(buffer))
    }

    // Authorization 헤더만 받는다. 이전에는 모든 경로에서 `?token=`도 받았지만, 토큰이 접근 로그·
    // 프록시 로그·브라우저 이력에 그대로 남는다. Kotlin 서버에는 WebSocket 엔드포인트가 없고
    // (앱의 `?token=`은 Python 서비스 `/ws/stream` 전용) 쿼리 토큰을 쓰는 호출부도 없어 제거했다(C7 ②).
    // Kotlin에 WS가 생기면 업그레이드 요청에 한해 되살린다.
    private fun extractToken(exchange: ServerWebExchange): String? =
        exchange.request.headers.getFirst(HttpHeaders.AUTHORIZATION)
            ?.takeIf { it.startsWith(BEARER_PREFIX) }
            ?.removePrefix(BEARER_PREFIX)
}
