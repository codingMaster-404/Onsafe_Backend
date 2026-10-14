package com.onsafe.backend.domain.camera.service

import com.onsafe.backend.common.util.guardRedis
import kotlinx.coroutines.reactor.awaitSingle
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.stereotype.Component

/**
 * WS 프레임 수신 생존 신호 조회 (카메라 스트리밍 B5).
 *
 * Python AI 서버가 `/ws/stream` 으로 프레임을 받는 동안 `stream:alive:{userId}` 를 30초마다 TTL 90초로
 * 갱신한다(`app/ai/buffer.py` `mark_stream_alive`). 키가 없으면 최근 90초간 프레임이 안 들어온 것이다.
 * 키 이름·TTL은 Python 쪽과 함께 바꿔야 한다. Kotlin은 읽기만 한다(TTL 관리는 쓰는 쪽 책임).
 */
@Component
class StreamAliveStore(private val redis: ReactiveStringRedisTemplate) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun key(userId: String): String = "stream:alive:$userId"

    /** [userIds] 중 생존 키가 있는 사용자. MGET 1회로 조회한다. */
    suspend fun aliveUserIds(userIds: List<String>): Set<String> {
        if (userIds.isEmpty()) return emptySet()
        val values = log.guardRedis("스트리밍 생존 키 조회") {
            redis.opsForValue().multiGet(userIds.map(::key)).awaitSingle()
        }
        return userIds.filterIndexed { index, _ -> values.getOrNull(index) != null }.toSet()
    }
}
