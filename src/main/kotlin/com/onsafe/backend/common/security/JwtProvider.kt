package com.onsafe.backend.common.security

import com.onsafe.backend.common.exception.ErrorCode
import io.jsonwebtoken.Claims
import io.jsonwebtoken.ExpiredJwtException
import io.jsonwebtoken.Header
import io.jsonwebtoken.JwtBuilder
import io.jsonwebtoken.JwtException
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.Date
import javax.crypto.SecretKey

private const val TYPE_CLAIM = "typ"
private const val AUTH_TIME_CLAIM = "auth_time"
private const val MIN_SECRET_BYTES = 32 // HS256 최소 키 길이 (256비트)

// Access와 Refresh의 클레임 구조가 같으면 한쪽 토큰을 다른 쪽 자리에 넣어도 검증을 통과한다.
// (30일짜리 refresh 토큰을 Authorization 헤더에 넣거나, access 토큰으로 /refresh를 호출)
// 발급 시 typ을 새기고 검증 시 기대 타입을 반드시 받도록 해 용도를 분리한다.
// Python 서비스(app/core/security.py)도 같은 값을 확인하므로 claimValue를 바꾸면 함께 바꿔야 한다.
enum class TokenType(val claimValue: String) {
    ACCESS("access"),
    REFRESH("refresh")
}

@Component
class JwtProvider(
    @Value("\${jwt.secret}") secret: String,
    @Value("\${jwt.access-token-expiry}") private val accessTokenExpiry: Long,
    @Value("\${jwt.refresh-token-expiry}") private val refreshTokenExpiry: Long
) {
    // lazy로 두면 JWT_SECRET이 비었거나 짧아도 기동은 성공하고 첫 인증 요청에서야 실패한다.
    // 빈 생성 시점에 검증해 잘못된 설정이 배포 단계(헬스체크)에서 드러나게 한다.
    private val signingKey: SecretKey = run {
        val bytes = secret.toByteArray()
        require(bytes.size >= MIN_SECRET_BYTES) {
            "jwt.secret(JWT_SECRET)은 ${MIN_SECRET_BYTES}바이트 이상이어야 합니다 (현재 ${bytes.size}바이트)"
        }
        Keys.hmacShaKeyFor(bytes)
    }

    fun generateAccessToken(userId: String): String {
        val now = Date()
        return baseBuilder(userId, TokenType.ACCESS, now)
            .expiration(Date(now.time + accessTokenExpiry))
            .compact()
    }

    // auth_time은 최초 로그인 시각이다. refresh로 재발급할 때 이전 토큰의 값을 그대로 넘겨받아
    // 만료를 "로그인 시각 + refresh 유효기간"에 고정한다 — 재발급마다 30일이 새로 시작되면
    // 탈취된 refresh 토큰을 서버에서 무기한 쓸 수 있다. 앱의 로컬 30일 정책과 기준이 같다.
    fun generateRefreshToken(userId: String, authTime: Instant): String {
        val authTimeSec = authTime.epochSecond
        return baseBuilder(userId, TokenType.REFRESH, Date())
            .claim(AUTH_TIME_CLAIM, authTimeSec)
            .expiration(Date(authTimeSec * 1000 + refreshTokenExpiry))
            .compact()
    }

    fun getUserId(token: String): String =
        parseClaims(token).subject

    fun getAuthTime(token: String): Instant =
        Instant.ofEpochSecond((parseClaims(token)[AUTH_TIME_CLAIM] as Number).toLong())

    /**
     * 토큰 유효성 검사 — 만료와 서명/형식/타입 오류를 구분해 ErrorCode로 반환.
     * null 이면 [expectedType] 용도로 쓸 수 있는 유효한 토큰.
     * typ이 없는 옛 토큰은 전환 처리 없이 INVALID_TOKEN이다.
     */
    fun getValidationError(token: String, expectedType: TokenType): ErrorCode? = try {
        if (hasExpectedShape(parseClaims(token), expectedType)) null else ErrorCode.INVALID_TOKEN
    } catch (e: ExpiredJwtException) {
        // jjwt는 서명 검증 후 exp 검증에서 예외를 던지므로 parseClaims의 알고리즘 확인과
        // 호출부의 타입 확인을 거치지 않는다. 같은 기준을 여기서도 적용해, 만료 전이면 INVALID인
        // 토큰이 만료 후 EXPIRED로 바뀌지 않게 한다 — EXPIRED는 "refresh하면 해결된다"는 뜻이라
        // 잘못된 토큰(다른 타입·typ 없는 옛 토큰·HS256 외 알고리즘)에 주면 클라이언트가 refresh를 시도한다.
        if (isAllowedAlgorithm(e.header) && hasExpectedShape(e.claims, expectedType)) {
            ErrorCode.EXPIRED_TOKEN
        } else {
            ErrorCode.INVALID_TOKEN
        }
    } catch (e: Exception) {
        ErrorCode.INVALID_TOKEN
    }

    fun getRemainingExpiry(token: String): Duration = runCatching {
        val remaining = parseClaims(token).expiration.time - System.currentTimeMillis()
        if (remaining > 0) Duration.ofMillis(remaining) else Duration.ZERO
    }.getOrDefault(Duration.ZERO)

    // Redis 키/슬로우로그/백업 등에 토큰 원문이 그대로 남지 않도록 해시로 치환한다.
    // 저장(AuthService)과 조회(JwtAuthenticationFilter) 양쪽이 반드시 이 함수 하나만 써야
    // 해시 방식이 어긋나 블랙리스트가 조용히 무력화되는 일이 없다.
    fun blacklistKey(token: String): String {
        val hash = MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))
        return "bl:" + hash.joinToString("") { "%02x".format(it) }
    }

    // subject에는 userId만 둔다. JWT payload는 base64라 누구나 읽을 수 있어 이메일 같은
    // 개인정보를 넣지 않고, 메일 변경 후 refresh 때 예전 메일로 재발급되는 문제도 없앤다.
    private fun baseBuilder(userId: String, type: TokenType, issuedAt: Date): JwtBuilder =
        Jwts.builder()
            .subject(userId)
            .claim(TYPE_CLAIM, type.claimValue)
            .issuedAt(issuedAt)
            .signWith(signingKey, Jwts.SIG.HS256)

    private fun hasExpectedShape(claims: Claims, expectedType: TokenType): Boolean {
        if (claims[TYPE_CLAIM] != expectedType.claimValue) return false
        if (claims.subject.isNullOrBlank()) return false
        return expectedType != TokenType.REFRESH || claims[AUTH_TIME_CLAIM] is Number
    }

    // verifyWith(key)만으로는 서명 검증만 할 뿐 헤더의 alg가 HS256인지는 확인하지 않는다 —
    // JWT_SECRET 바이트 길이가 HS384/HS512에도 유효한 키라면, 그 알고리즘을 자칭하는 토큰도
    // 같은 키로 유효한 서명을 만들 수 있어 검증을 통과해버린다. 발급 측을 HS256으로 고정한
    // 의도가 검증 측까지 온전히 지켜지도록 헤더의 알고리즘을 명시적으로 다시 확인한다.
    // (그 토큰도 JWT_SECRET이 있어야 만들 수 있어 외부 공격 차단이 아니라, 허용 알고리즘을
    // 검증 측에 명시하고 Python 서비스의 algorithms=["HS256"]과 판정을 맞추기 위한 방어선이다.)
    private fun parseClaims(token: String): Claims {
        val jws = Jwts.parser().verifyWith(signingKey).build().parseSignedClaims(token)
        if (!isAllowedAlgorithm(jws.header)) {
            throw JwtException("Unexpected JWS algorithm: ${jws.header.algorithm}")
        }
        return jws.payload
    }

    private fun isAllowedAlgorithm(header: Header): Boolean =
        header.algorithm == Jwts.SIG.HS256.id
}
