package com.onsafe.backend.config

import com.onsafe.backend.common.security.JwtAuthenticationFilter
import com.onsafe.backend.common.security.SecurityPaths
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity
import org.springframework.security.config.web.server.SecurityWebFiltersOrder
import org.springframework.security.config.web.server.ServerHttpSecurity
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.web.server.SecurityWebFilterChain

@Configuration
@EnableWebFluxSecurity
class SecurityConfig(
    private val jwtAuthenticationFilter: JwtAuthenticationFilter
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
            // AUTHENTICATION 단계 직전에 JWT 필터 삽입 → SecurityContext 세팅 후 인가 검사
            .addFilterBefore(jwtAuthenticationFilter, SecurityWebFiltersOrder.AUTHENTICATION)
            .build()
    }

    // BCrypt: 단방향 해시 + salt 자동 생성, 같은 비밀번호도 매번 다른 해시값 생성
    @Bean
    fun passwordEncoder(): PasswordEncoder = BCryptPasswordEncoder()
}