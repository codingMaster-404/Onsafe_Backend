package com.onsafe.backend.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.onsafe.backend.common.security.JwtAuthenticationFilter
import com.onsafe.backend.common.security.JwtProvider
import com.onsafe.backend.common.security.SecurityPaths
import com.onsafe.backend.common.security.TokenRevocationStore
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity
import org.springframework.security.config.web.server.SecurityWebFiltersOrder
import org.springframework.security.config.web.server.ServerHttpSecurity
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.web.server.SecurityWebFilterChain

@Configuration
@EnableWebFluxSecurity
class SecurityConfig(
    private val jwtProvider: JwtProvider,
    private val redis: ReactiveStringRedisTemplate,
    private val objectMapper: ObjectMapper,
    private val tokenRevocationStore: TokenRevocationStore,
) {

    @Bean
    fun securityFilterChain(http: ServerHttpSecurity): SecurityWebFilterChain {
        return http
            .csrf { it.disable() }       // REST API는 CSRF 불필요 (쿠키 기반 세션 미사용)
            .httpBasic { it.disable() }  // Basic Auth 비활성화
            .formLogin { it.disable() }  // 폼 로그인 비활성화
            .authorizeExchange { auth ->
                auth
                    .pathMatchers(*SecurityPaths.PUBLIC_PATTERNS).permitAll()
                    .anyExchange().authenticated()
            }
            // AUTHENTICATION 단계 직전에 JWT 필터 삽입 → SecurityContext 세팅 후 인가 검사.
            // 필터는 여기서만 만든다 — @Bean·@Component로 두면 일반 필터 체인에도 등록돼 두 번 실행된다(M4).
            .addFilterBefore(
                JwtAuthenticationFilter(jwtProvider, redis, objectMapper, tokenRevocationStore),
                SecurityWebFiltersOrder.AUTHENTICATION
            )
            .build()
    }

    // BCrypt: 단방향 해시 + salt 자동 생성, 같은 비밀번호도 매번 다른 해시값 생성
    @Bean
    fun passwordEncoder(): PasswordEncoder = BCryptPasswordEncoder()
}