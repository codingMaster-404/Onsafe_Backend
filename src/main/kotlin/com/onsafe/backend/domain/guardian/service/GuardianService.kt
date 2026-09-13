package com.onsafe.backend.domain.guardian.service

import com.onsafe.backend.common.exception.BusinessException
import com.onsafe.backend.common.exception.ErrorCode
import com.onsafe.backend.common.ratelimit.RateLimiter
import com.onsafe.backend.common.security.VerificationCodeGenerator
import com.onsafe.backend.common.util.guardRedis
import com.onsafe.backend.domain.guardian.model.dto.GuardianResponse
import com.onsafe.backend.domain.guardian.model.dto.PairingCodeResponse
import com.onsafe.backend.domain.guardian.model.dto.PairingRequestResponse
import com.onsafe.backend.domain.guardian.model.dto.WardResponse
import com.onsafe.backend.domain.guardian.model.entity.GuardianLink
import com.onsafe.backend.domain.guardian.repository.GuardianLinkRepository
import com.onsafe.backend.domain.notification.model.dto.NotificationRequest
import com.onsafe.backend.domain.notification.service.NotificationService
import com.onsafe.backend.domain.user.repository.UserRepository
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactive.awaitSingle
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.data.redis.core.script.RedisScript
import org.springframework.stereotype.Service
import java.time.Duration
import java.util.UUID

// 5분에서 15분으로 상향 — 노인 피보호자가 보호자에게 코드 전달하는 사이 만료돼 재발급이
// 반복되는 UX 문제(20번째 회의 결정)와, 자동 갱신 주기가 (TTL-10초) 기준이라 5분 TTL 시
// 시간당 12회 요청 → GuardianService.issuePairingCode rate limit(5회/시간) 초과가 25분 만에
// 발생하는 문제를 함께 완화한다. 15분이면 자동 갱신이 시간당 4회로 낮아져 자연 해결.
private const val PAIRING_CODE_TTL = 900L  // 15분

// 승인 대기 요청 만료. 피보호자가 확인·결정할 여유 시간. 너무 짧으면 노인 피보호자가 다른 일
// 하는 중에 만료되기 쉽고, 너무 길면 오래된 요청이 화면에 남아 혼란. 30분 정도가 적절.
private const val PAIRING_REQUEST_TTL = 1800L  // 30분

@Service
class GuardianService(
    private val guardianLinkRepository: GuardianLinkRepository,
    private val userRepository: UserRepository,
    private val notificationService: NotificationService,
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

        // 1:1 정책: 이미 다른 보호자와 연결된 상태면 새 코드 발급 자체를 거부한다. 발급을 허용하면
        // 노출된 코드가 무의미하게 15분 동안 살아있게 되고, 사용자가 "왜 연결이 안 되지?"
        // 혼란만 커진다. 재발급이 필요한 유일한 정상 시나리오는 "기존 연결 해제 후 새 보호자"뿐.
        if (guardianLinkRepository.existsByElder(elderUserId)) {
            throw BusinessException(ErrorCode.ELDER_ALREADY_HAS_GUARDIAN)
        }
        // 역할 배타성: 이 계정이 이미 다른 관계에서 보호자로 활동 중이면 피보호자용 코드 발급도 거부.
        if (guardianLinkRepository.existsByGuardian(elderUserId)) {
            throw BusinessException(ErrorCode.ROLE_CONFLICT_ALREADY_GUARDIAN)
        }

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

    // 코드를 입력한 보호자가 즉시 관계를 성립시키는 대신, 피보호자 승인 대기 상태로 요청을
    // 남기고 피보호자에게 FCM 으로 통지한다. 코드 유출·어깨너머로 훔쳐본 사람이 마음대로
    // 카메라에 접근하는 스토킹 벡터를 차단하기 위함(21번째 회의 §7b).
    // 반환값은 즉시 elder 정보가 아니라 요청 식별자·만료 시각 — 실제 성립은 피보호자가 승인해야
    // 이뤄지고, 결과는 별도 FCM 으로 보호자에게 전달된다.
    suspend fun pair(guardianUserId: String, code: String, ipAddress: String): PairingRequestResponse {
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
        val guardian = userRepository.findByUserId(guardianUserId)
            ?: throw BusinessException(ErrorCode.USER_NOT_FOUND)

        // 팀 결정: "교체 + 승인" — 1:1 위반(이미 다른 상대와 페어링됨)은 거부하지 않고, 피보호자
        // 승인 시 기존 관계를 자동으로 교체한다. 다만 역할 배타성(guardian↔elder 겸용)은 교체할
        // 수 있는 축이 아니라 여기서 여전히 거부. 요청 자체는 approvePairingRequest 에서 다시
        // 검증하지만, 명백히 실패할 요청까지 피보호자 FCM 을 띄우는 건 UX 낭비.
        if (guardianLinkRepository.existsByElder(guardianUserId)) {
            throw BusinessException(ErrorCode.ROLE_CONFLICT_ALREADY_ELDER)
        }
        if (guardianLinkRepository.existsByGuardian(elderUserId)) {
            throw BusinessException(ErrorCode.ROLE_CONFLICT_ALREADY_GUARDIAN)
        }

        // 승인 요청 FCM 문구를 정확히 구성하기 위해 기존 관계(교체될 대상) 정보를 미리 조회.
        // 승인 시점의 트랜잭션(createOrReplace) 내부에서도 다시 검사되지만, 사용자가 승인 다이얼
        // 로그에서 "누구를 대체하는지" 미리 알아야 결정할 수 있다.
        val existingGuardianOfElder = guardianLinkRepository.findGuardiansOf(elderUserId).firstOrNull()
            ?.let { userRepository.findByUserId(it) }
        val existingWardOfGuardian = guardianLinkRepository.findWardsOf(guardianUserId).firstOrNull()
            ?.let { userRepository.findByUserId(it.elderUserId) }

        // 승인 대기 요청 생성. Redis 에 guardianUserId·elderUserId 쌍을 저장하고 30분 TTL 로 자연
        // 만료시킨다. 요청 ID 는 UUID — 예측 불가해야 승인 엔드포인트 브루트포스가 무의미해짐.
        val requestId = UUID.randomUUID().toString()
        val payload = "$guardianUserId:$elderUserId"
        redisGuarded {
            redis.opsForValue()
                .set("pairing_request:$requestId", payload, Duration.ofSeconds(PAIRING_REQUEST_TTL))
                .awaitSingle()
        }

        // 피보호자에게 FCM 통지 — 승인 다이얼로그에 "기존 X 와의 연결이 해제됩니다" 안내가 함께
        // 뜨도록 body 를 상황에 맞게 구성. 데이터 페이로드에도 대체 대상 정보를 담아 프론트가
        // 화면 구성에 활용 가능.
        val body = buildString {
            append("${guardian.name}님이 보호자로 연결을 요청했습니다.")
            if (existingGuardianOfElder != null) {
                append(" 승인 시 현재 보호자 ${existingGuardianOfElder.name}님과의 연결이 해제됩니다.")
            }
        }
        val data = buildMap {
            put("event", "pairing_request")
            put("request_id", requestId)
            put("guardian_user_id", guardianUserId)
            put("guardian_name", guardian.name)
            existingGuardianOfElder?.let {
                put("displaces_guardian_user_id", it.userId)
                put("displaces_guardian_name", it.name)
            }
            existingWardOfGuardian?.let {
                put("guardian_leaves_elder_user_id", it.userId)
                put("guardian_leaves_elder_name", it.name)
            }
        }
        runCatching {
            notificationService.sendNotification(
                NotificationRequest(userId = elderUserId, title = "보호자 연결 요청", body = body, data = data)
            )
        }.onFailure { e -> log.warn("페어링 요청 FCM 실패 — elderUserId=$elderUserId cause=${e.message}") }

        return PairingRequestResponse(requestId = requestId, expiresInSeconds = PAIRING_REQUEST_TTL)
    }

    // 피보호자 승인 진입점. 요청 ID 를 Redis 에서 원자적으로 소비(GETDEL)해 이중 승인 방지.
    // 실제 관계 생성은 createOrReplace 트랜잭션이 담당 — 요청 생성과 승인 사이에 다른 관계가
    // 새로 맺어졌어도 그 관계를 자동 삭제하고 이 요청을 성립시킨다("교체 + 승인" 정책).
    // 역할 배타성 위반만 여전히 거부 — 이건 교체할 수 있는 축이 아님.
    // 대체된 파트너(들)에게는 "당신은 이제 A의 보호자가 아닙니다" FCM 을 발송한다.
    suspend fun approvePairingRequest(elderUserId: String, requestId: String): WardResponse {
        val payload = redisGuarded {
            redis.opsForValue().getAndDelete("pairing_request:$requestId").awaitFirstOrNull()
        } ?: throw BusinessException(ErrorCode.PAIRING_REQUEST_INVALID)

        val parts = payload.split(":", limit = 2)
        if (parts.size != 2) throw BusinessException(ErrorCode.PAIRING_REQUEST_INVALID)
        val (guardianUserId, storedElderUserId) = parts

        // 다른 사용자가 남의 요청 ID 를 승인하려 하면 거부. Redis 소비는 이미 됐지만 실제 성립을
        // 막아 부수효과를 봉쇄한다. 소비된 요청은 자동 폐기되므로 재사용 불가.
        if (storedElderUserId != elderUserId) {
            throw BusinessException(ErrorCode.FORBIDDEN)
        }

        val elder = userRepository.findByUserId(elderUserId)
            ?: throw BusinessException(ErrorCode.USER_NOT_FOUND)
        val guardian = userRepository.findByUserId(guardianUserId)
            ?: throw BusinessException(ErrorCode.USER_NOT_FOUND)

        val result = guardianLinkRepository.createOrReplace(
            GuardianLink(guardianUserId = guardianUserId, elderUserId = elderUserId)
        )
        val displaced = when (result) {
            is GuardianLinkRepository.ReplaceResult.Created -> result.displaced
            GuardianLinkRepository.ReplaceResult.SamePairExists ->
                throw BusinessException(ErrorCode.PAIRING_ALREADY_EXISTS)
            GuardianLinkRepository.ReplaceResult.GuardianIsElderElsewhere ->
                throw BusinessException(ErrorCode.ROLE_CONFLICT_ALREADY_ELDER)
            GuardianLinkRepository.ReplaceResult.ElderIsGuardianElsewhere ->
                throw BusinessException(ErrorCode.ROLE_CONFLICT_ALREADY_GUARDIAN)
        }

        // 대체된 파트너(들)에게 해제 통지 — "당신은 이제 A의 보호자가 아닙니다" 성격.
        // 이 새 페어링에 참여하는 두 당사자(guardian, elder)는 제외해야 자기 자신이 자기 페어링에
        // 대해 해제 알림을 받는 어색함이 안 생긴다.
        displaced.forEach { oldLink ->
            val recipient = if (oldLink.guardianUserId == guardianUserId) oldLink.elderUserId
                            else oldLink.guardianUserId
            if (recipient == guardianUserId || recipient == elderUserId) return@forEach
            val role = if (oldLink.guardianUserId == recipient) "보호자" else "피보호자"
            runCatching {
                notificationService.sendNotification(
                    NotificationRequest(
                        userId = recipient,
                        title = "$role 연결 해제됨",
                        body = "상대방이 새로운 연결을 맺어 기존 연결이 자동으로 해제되었습니다.",
                        data = mapOf(
                            "event" to "pairing_displaced",
                            "old_guardian_user_id" to oldLink.guardianUserId,
                            "old_elder_user_id" to oldLink.elderUserId,
                        )
                    )
                )
            }.onFailure { e -> log.warn("교체 알림 실패 — recipient=$recipient cause=${e.message}") }
        }

        // 성립 통지 — 보호자에게 승인 완료 FCM. 피보호자는 자기가 승인한 것이라 별도 알림 불필요.
        runCatching {
            notificationService.sendNotification(
                NotificationRequest(
                    userId = guardianUserId,
                    title = "보호자 연결 완료",
                    body = "${elder.name}님과 연결되었습니다.",
                    data = mapOf(
                        "event" to "pairing_approved",
                        "elder_user_id" to elderUserId,
                        "elder_name" to elder.name,
                    )
                )
            )
        }.onFailure { e -> log.warn("페어링 승인 FCM 실패 — guardianUserId=$guardianUserId cause=${e.message}") }

        return WardResponse.from(elder)
    }

    // 피보호자 거부 진입점. Redis 요청을 소비하고 보호자에게 거부 통지. 요청 ID 만 알면 아무나
    // 거부할 수 있으면 안 되므로 승인과 동일하게 payload elder 매칭 확인.
    suspend fun rejectPairingRequest(elderUserId: String, requestId: String) {
        val payload = redisGuarded {
            redis.opsForValue().getAndDelete("pairing_request:$requestId").awaitFirstOrNull()
        } ?: throw BusinessException(ErrorCode.PAIRING_REQUEST_INVALID)

        val parts = payload.split(":", limit = 2)
        if (parts.size != 2) throw BusinessException(ErrorCode.PAIRING_REQUEST_INVALID)
        val (guardianUserId, storedElderUserId) = parts
        if (storedElderUserId != elderUserId) {
            throw BusinessException(ErrorCode.FORBIDDEN)
        }

        val elder = userRepository.findByUserId(elderUserId)
        runCatching {
            notificationService.sendNotification(
                NotificationRequest(
                    userId = guardianUserId,
                    title = "보호자 연결 거부됨",
                    body = "${elder?.name ?: "피보호자"}님이 연결 요청을 거부했습니다.",
                    data = mapOf(
                        "event" to "pairing_rejected",
                        "elder_user_id" to elderUserId,
                    )
                )
            )
        }.onFailure { e -> log.warn("페어링 거부 FCM 실패 — guardianUserId=$guardianUserId cause=${e.message}") }
    }

    suspend fun getWards(guardianUserId: String): List<WardResponse> =
        guardianLinkRepository.findWardsOf(guardianUserId)
            .mapNotNull { link -> userRepository.findByUserId(link.elderUserId)?.let(WardResponse::from) }

    // 피보호자용 — 자기 보호자를 확인. 1:1 정책상 최대 한 명이라 List 대신 단일 객체.
    suspend fun getMyGuardian(elderUserId: String): GuardianResponse? {
        val guardianIds = guardianLinkRepository.findGuardiansOf(elderUserId)
        val guardianId = guardianIds.firstOrNull() ?: return null
        return userRepository.findByUserId(guardianId)?.let(GuardianResponse::from)
    }

    // 어느 쪽(보호자/피보호자)이 호출했는지 몰라도 되도록 양방향 문서 ID를 모두 시도한다.
    // 해제 성립 후 상대방에게 FCM 통지 — "누가 언제 끊었는지" 상대가 알 수 있어야 함(21번째 회의 §7c).
    suspend fun unpair(userId: String, counterpartUserId: String) {
        val deleted = guardianLinkRepository.delete(userId, counterpartUserId) ||
            guardianLinkRepository.delete(counterpartUserId, userId)
        if (!deleted) throw BusinessException(ErrorCode.PAIRING_NOT_FOUND)

        val initiator = userRepository.findByUserId(userId)
        runCatching {
            notificationService.sendNotification(
                NotificationRequest(
                    userId = counterpartUserId,
                    title = "보호자 연결 해제",
                    body = "${initiator?.name ?: userId}님이 연결을 해제했습니다.",
                    data = mapOf(
                        "event" to "pairing_unpaired",
                        "initiator_user_id" to userId,
                    )
                )
            )
        }.onFailure { e -> log.warn("페어링 해제 FCM 실패 — counterpartUserId=$counterpartUserId cause=${e.message}") }
    }

    // RateLimiter.requireAllowed와 동일한 래핑 정책을 common/util/RedisExt.kt의 guardRedis로 공유한다 —
    // 정책이 바뀔 때 한쪽만 고치고 다른 쪽을 놓치는 일을 막기 위함.
    private suspend fun <T> redisGuarded(block: suspend () -> T): T =
        log.guardRedis("guardian pairing", block)
}
