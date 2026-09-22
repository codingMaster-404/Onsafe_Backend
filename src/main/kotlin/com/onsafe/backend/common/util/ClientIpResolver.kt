package com.onsafe.backend.common.util

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange

private const val UNKNOWN_IP = "unknown"
private const val FORWARDED_FOR = "X-Forwarded-For"

/**
 * rate limit·로그인 이력에 쓰는 클라이언트 IP를 구한다.
 *
 * `X-Forwarded-For`는 `클라이언트가 보낸 값…, 실제 클라이언트 IP, 프록시…` 순으로 쌓인다.
 * **왼쪽은 클라이언트가 마음대로 넣을 수 있고**, 오른쪽 끝부터가 우리 인프라가 붙인 값이다.
 * 예전처럼 첫 값을 쓰면 `X-Forwarded-For: 1.2.3.4`를 직접 넣어 IP 기준 rate limit을 우회하고
 * 로그인 이력의 IP도 위조할 수 있다(C3).
 *
 * 그래서 **오른쪽에서 [trustedProxyCount]번째** 값만 신뢰한다.
 * - Cloud Run 직접 노출(현재 배포: `--allow-unauthenticated`, ingress 기본값) → **1**
 * - 앞단에 외부 로드밸런서를 두면 → **2** (환경변수 `TRUSTED_PROXY_COUNT`로 조정, 코드 수정 불필요)
 *
 * 헤더가 기대보다 짧으면(= 신뢰 구간이 없으면) 위조 가능한 값을 쓰는 대신 직접 연결된
 * 상대 주소로 떨어진다. 이 값은 인증이 아니라 rate limit·이력에만 쓰이므로 fail-closed가 아니라
 * "덜 정확한 값"으로 물러나는 편이 서비스 영향이 작다.
 */
@Component
class ClientIpResolver(
    @Value("\${app.trusted-proxy-count}") private val trustedProxyCount: Int
) {
    fun resolve(exchange: ServerWebExchange): String {
        val forwarded = exchange.request.headers.getFirst(FORWARDED_FOR)
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()

        val trustedIndex = forwarded.size - trustedProxyCount
        if (trustedIndex in forwarded.indices) return forwarded[trustedIndex]

        return exchange.request.remoteAddress?.address?.hostAddress ?: UNKNOWN_IP
    }
}
