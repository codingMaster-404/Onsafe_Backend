package com.onsafe.backend.domain.live.repository

import com.onsafe.backend.common.util.guardRedis
import com.onsafe.backend.domain.live.model.entity.LiveSession
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactor.awaitSingle
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.stereotype.Repository
import java.time.Duration
import java.time.Instant

/**
 * `live:session:{elderUserId}` = "{startedBy}|{expiresAt epoch 초}".
 * TTL을 세션 만료 시각에 맞춰 두므로 끝난 세션은 Redis가 지운다(키가 남아 쌓이지 않음).
 */
@Repository
class LiveSessionRepository(private val redis: ReactiveStringRedisTemplate) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun key(elderUserId: String) = "live:session:$elderUserId"

    suspend fun find(elderUserId: String): LiveSession? {
        val value = log.guardRedis("실시간 영상 세션 조회") {
            redis.opsForValue().get(key(elderUserId)).awaitFirstOrNull()
        } ?: return null
        return parse(elderUserId, value)
    }

    suspend fun save(session: LiveSession, now: Instant = Instant.now()) {
        val ttl = Duration.between(now, session.expiresAt)
        if (ttl.isNegative || ttl.isZero) return
        log.guardRedis("실시간 영상 세션 저장") {
            redis.opsForValue()
                .set(key(session.elderUserId), "${session.startedBy}|${session.expiresAt.epochSecond}", ttl)
                .awaitSingle()
        }
    }

    suspend fun delete(elderUserId: String): Boolean =
        log.guardRedis("실시간 영상 세션 삭제") { redis.delete(key(elderUserId)).awaitSingle() > 0 }

    // 이 클래스만 쓰는 키라 형식이 깨질 일은 없지만, 깨진 값이면 세션이 없는 것으로 본다(송출 토큰을 내주지 않음).
    private fun parse(elderUserId: String, value: String): LiveSession? {
        val parts = value.split("|")
        if (parts.size != 2 || parts[0].isBlank()) return null
        val expiresAt = parts[1].toLongOrNull()?.let { Instant.ofEpochSecond(it) } ?: return null
        return LiveSession(elderUserId = elderUserId, startedBy = parts[0], expiresAt = expiresAt)
    }
}
