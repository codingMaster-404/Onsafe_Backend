package com.onsafe.backend.domain.guardian.service

import com.onsafe.backend.common.exception.BusinessException
import com.onsafe.backend.common.exception.ErrorCode
import com.onsafe.backend.common.ratelimit.RateLimiter
import com.onsafe.backend.common.security.VerificationCodeGenerator
import com.onsafe.backend.common.util.guardRedis
import com.onsafe.backend.domain.guardian.model.dto.PairingCodeResponse
import com.onsafe.backend.domain.guardian.model.dto.WardResponse
import com.onsafe.backend.domain.guardian.model.entity.GuardianLink
import com.onsafe.backend.domain.guardian.repository.GuardianLinkRepository
import com.onsafe.backend.domain.user.repository.UserRepository
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactive.awaitSingle
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.data.redis.core.script.RedisScript
import org.springframework.stereotype.Service

private const val PAIRING_CODE_TTL = 300L  // 5분

@Service
class GuardianService(
    private val guardianLinkRepository: GuardianLinkRepository,
    private val userRepository: UserRepository,
    private val redis: ReactiveStringRedisTemplate,
    private val rateLimiter: RateLimiter,
    private val verificationCodeGenerator: VerificationCodeGenerator
) {

    private val log = LoggerFactory.getLogger(javaClass)

    // 코드 중복 여부 확인, 이전 코드 무효화, 신규 발급을 한 스크립트 안에서 원자적으로 처리한다.
    // 이전 코드 무효화(GET ownerKey → DEL pairing_code:oldCode)를 별도 호출로 하면 그 사이에
    // pair()가 old code를 소비하고 ownerKey를 갱신하는 흐름과 인터리빙되어, 코드가 2개 동시에
    // 유효해지거나 방금 발급한 새 코드의 소유권 매핑이 사라지는 경쟁 상태가 생길 수 있다.
    // KEYS[1]=pairing_code:$code(신규), KEYS[2]=ownerKey. 신규 코드가 이미 존재하면 0, 발급 성공하면 1.
    private val issueCodeScript: RedisScript<Long> = RedisScript.of(
        """
        local oldCode = redis.call('GET', KEYS[2])
        if oldCode then
            redis.call('DEL', 'pairing_code:' .. oldCode)
        end
        if redis.call('EXISTS', KEYS[1]) == 1 then
            return 0
        end
        redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[3])
        redis.call('SET', KEYS[2], ARGV[2], 'EX', ARGV[3])
        return 1
        """.trimIndent(),
        Long::class.java
    )

    // pair()가 코드를 소비한 뒤 ownerKey를 지울 때, 그 사이 issuePairingCode가 새 코드를 발급해
    // ownerKey를 이미 갱신했다면 무조건 삭제해선 안 된다 — 방금 발급된 새 코드의 소유권 매핑까지
    // 함께 지워지는 경쟁 상태가 생긴다. ownerKey 값이 여전히 방금 소비한 코드일 때만 지운다
    // (compare-and-delete). KEYS[1]=ownerKey, ARGV[1]=소비한 code.
    private val deleteOwnerIfMatchScript: RedisScript<Long> = RedisScript.of(
        """
        if redis.call('GET', KEYS[1]) == ARGV[1] then
            return redis.call('DEL', KEYS[1])
        end
        return 0
        """.trimIndent(),
        Long::class.java
    )

    // 유저당 활성 코드는 1개만 유지 — 재발급 시 이전 코드를 먼저 무효화해
    // 캡처/전달 과정에서 노출된 옛 코드가 계속 살아있지 않게 한다.
    suspend fun issuePairingCode(elderUserId: String): PairingCodeResponse {
        // 재발급 남용 방지 — sendEmailCode/sendResetCode(시간당 3회)와 동일한 원칙.
        rateLimiter.requireAllowed("rl:issue-pairing-code:$elderUserId", limit = 5, windowSec = 3600)

        return redisGuarded {
            val ownerKey = "pairing_code_owner:$elderUserId"
            lateinit var code: String
            while (true) {
                code = verificationCodeGenerator.generate()
                val claimed = redis.execute(
                    issueCodeScript,
                    listOf("pairing_code:$code", ownerKey),
                    listOf(elderUserId, code, PAIRING_CODE_TTL.toString())
                ).awaitSingle()
                if (claimed == 1L) break
            }

            PairingCodeResponse(code = code, expiresInSeconds = PAIRING_CODE_TTL)
        }
    }

    suspend fun pair(guardianUserId: String, code: String, ipAddress: String): WardResponse {
        // 이중 rate-limit: IP는 여러 계정을 만들어 우회하는 자동화 시도 대응, guardianUserId는
        // 코드 공간(10^6)에 대한 특정 계정 브루트포스 대응. AuthService.login과 동일한 원칙.
        rateLimiter.requireAllowed("rl:pair:ip:$ipAddress", limit = 30, windowSec = PAIRING_CODE_TTL)
        rateLimiter.requireAllowed("rl:pair:uid:$guardianUserId", limit = 10, windowSec = PAIRING_CODE_TTL)

        // GETDEL로 조회와 즉시 무효화를 원자적으로 묶는다 — 같은 코드로 pair()가 동시에 호출돼도
        // Redis가 원자적으로 처리하므로 정확히 한 요청만 elderUserId를 얻고, 나머지는 코드가 이미
        // 지워진 상태라 PAIRING_CODE_INVALID로 실패한다(GET 후 뒤늦게 DELETE하면 그 사이 창에서
        // 같은 코드가 서로 다른 보호자에게 이중으로 소비될 수 있었음).
        // 이 시점 이후의 검증(자기 자신/이미 연결됨)이 실패해도 코드는 이미 소진된다 — 의도적 trade-off.
        // 두 실패 케이스 모두 같은 코드로 재시도해도 동일하게 실패하므로(자기 자신이라는 사실도,
        // 이미 연결됐다는 사실도 코드를 새로 받는다고 바뀌지 않음) 재발급을 요구해도 실질적 손해가 없다.
        val elderUserId = redisGuarded {
            redis.opsForValue().getAndDelete("pairing_code:$code").awaitFirstOrNull()
        } ?: throw BusinessException(ErrorCode.PAIRING_CODE_INVALID)
        redisGuarded {
            redis.execute(
                deleteOwnerIfMatchScript,
                listOf("pairing_code_owner:$elderUserId"),
                listOf(code)
            ).awaitSingle()
        }

        if (elderUserId == guardianUserId) throw BusinessException(ErrorCode.SELF_PAIRING_NOT_ALLOWED)

        val elder = userRepository.findByUserId(elderUserId)
            ?: throw BusinessException(ErrorCode.USER_NOT_FOUND)

        val created = guardianLinkRepository.createIfNotExists(
            GuardianLink(guardianUserId = guardianUserId, elderUserId = elderUserId)
        )
        if (!created) throw BusinessException(ErrorCode.PAIRING_ALREADY_EXISTS)

        return WardResponse.from(elder)
    }

    suspend fun getWards(guardianUserId: String): List<WardResponse> =
        guardianLinkRepository.findWardsOf(guardianUserId)
            .mapNotNull { link -> userRepository.findByUserId(link.elderUserId)?.let(WardResponse::from) }

    // 어느 쪽(보호자/피보호자)이 호출했는지 몰라도 되도록 양방향 문서 ID를 모두 시도한다.
    suspend fun unpair(userId: String, counterpartUserId: String) {
        val deleted = guardianLinkRepository.delete(userId, counterpartUserId) ||
            guardianLinkRepository.delete(counterpartUserId, userId)
        if (!deleted) throw BusinessException(ErrorCode.PAIRING_NOT_FOUND)
    }

    // RateLimiter.requireAllowed와 동일한 래핑 정책을 common/util/RedisExt.kt의 guardRedis로 공유한다 —
    // 정책이 바뀔 때 한쪽만 고치고 다른 쪽을 놓치는 일을 막기 위함.
    private suspend fun <T> redisGuarded(block: suspend () -> T): T =
        log.guardRedis("guardian pairing", block)
}
