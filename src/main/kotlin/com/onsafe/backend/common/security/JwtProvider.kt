package com.onsafe.backend.common.security

import com.onsafe.backend.common.exception.ErrorCode
import io.jsonwebtoken.Claims
import io.jsonwebtoken.ExpiredJwtException
import io.jsonwebtoken.JwtException
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.Date
import javax.crypto.SecretKey

@Component
class JwtProvider(
    @Value("\${jwt.secret}") private val secret: String,
    @Value("\${jwt.access-token-expiry}") private val accessTokenExpiry: Long,
    @Value("\${jwt.refresh-token-expiry}") private val refreshTokenExpiry: Long
) {
    private val signingKey: SecretKey by lazy {
        Keys.hmacShaKeyFor(secret.toByteArray())
    }

    fun generateAccessToken(userId: String, email: String): String =
        buildToken(userId, email, accessTokenExpiry)

    fun generateRefreshToken(userId: String, email: String): String =
        buildToken(userId, email, refreshTokenExpiry)

    fun getUserId(token: String): String =
        parseClaims(token)["userId"].toString()

    fun getEmail(token: String): String =
        parseClaims(token).subject

    /**
     * 토큰 유효성 검사 — 만료와 서명/형식 오류를 구분해 ErrorCode로 반환.
     * null 이면 유효한 토큰.
     */
    fun getValidationError(token: String): ErrorCode? = try {
        parseClaims(token)
        null
    } catch (e: ExpiredJwtException) {
        ErrorCode.EXPIRED_TOKEN
    } catch (e: Exception) {
        ErrorCode.INVALID_TOKEN
    }

    fun validate(token: String): Boolean = getValidationError(token) == null

    fun getRemainingExpiry(token: String): Duration = runCatching {
        val remaining = parseClaims(token).expiration.time - System.currentTimeMillis()
        if (remaining > 0) Duration.ofMillis(remaining) else Duration.ZERO
    }.getOrDefault(Duration.ZERO)

    private fun buildToken(userId: String, email: String, expiry: Long): String {
        val now = Date()
        return Jwts.builder()
            .subject(email)
            .claim("userId", userId)
            .issuedAt(now)
            .expiration(Date(now.time + expiry))
            .signWith(signingKey, Jwts.SIG.HS256)
            .compact()
    }

    // verifyWith(key)만으로는 서명 검증만 할 뿐 헤더의 alg가 HS256인지는 확인하지 않는다 —
    // JWT_SECRET 바이트 길이가 HS384/HS512에도 유효한 키라면, 그 알고리즘을 자칭하는 토큰도
    // 같은 키로 유효한 서명을 만들 수 있어 검증을 통과해버린다. 발급 측을 HS256으로 고정한
    // 의도가 검증 측까지 온전히 지켜지도록 헤더의 알고리즘을 명시적으로 다시 확인한다.
    private fun parseClaims(token: String): Claims {
        val jws = Jwts.parser().verifyWith(signingKey).build().parseSignedClaims(token)
        if (jws.header.algorithm != Jwts.SIG.HS256.id) {
            throw JwtException("Unexpected JWS algorithm: ${jws.header.algorithm}")
        }
        return jws.payload
    }
}
