package com.onsafe.backend.common.util

import org.springframework.web.server.ServerWebExchange

// 로드밸런서/리버스 프록시 뒤에서는 remoteAddress가 프록시 IP라 X-Forwarded-For를 우선 신뢰한다.
// 클라이언트가 이 헤더를 위조할 수 있어 완전한 신뢰 경계는 아니지만(인프라 레벨 프록시 검증 전까지는
// rate-limit의 "심층 방어" 계층 중 하나일 뿐), rl:*:uid 제한이 최종 방어선 역할을 한다.
fun ServerWebExchange.clientIpAddress(): String =
    request.headers.getFirst("X-Forwarded-For")?.split(",")?.first()?.trim()
        ?: request.remoteAddress?.address?.hostAddress
        ?: "unknown"
