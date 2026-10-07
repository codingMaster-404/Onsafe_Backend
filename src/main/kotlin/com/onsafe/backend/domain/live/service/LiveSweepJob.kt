package com.onsafe.backend.domain.live.service

import com.onsafe.backend.common.livekit.LiveKitRoomService
import com.onsafe.backend.domain.live.repository.LiveSessionRepository
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * 만료된 실시간 영상 방 정리(W6) — Cloud Scheduler가 1분마다 `POST /internal/jobs/live-sweep`로 호출한다.
 *
 * 세션 만료는 Redis TTL이 이미 표시하므로(키가 사라짐), LiveKit에 남은 `live-*` 방 중 **Redis 세션이 없는 방**을 지우면 된다.
 * 강제 종료 경로(연결 해제 등)에서 방 삭제가 실패해 남은 방도 여기서 함께 청소된다. 끊김은 만료 후 최대 약 1분 늦다.
 *
 * Redis를 확인하지 못한 방은 지우지 않는다(진행 중 세션을 잘못 끊지 않도록) — 다음 회차에 다시 본다.
 * Cloud Run min-instances=0 이라 앱 내부 스케줄러 대신 Cloud Scheduler 트리거를 쓴다(다른 내부 잡과 같은 이유).
 */
@Component
class LiveSweepJob(
    private val roomService: LiveKitRoomService,
    private val liveSessionRepository: LiveSessionRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        const val ROOM_PREFIX = "live-"
    }

    suspend fun run() {
        val rooms = roomService.listRoomNames() ?: return
        var deleted = 0
        rooms.filter { it.startsWith(ROOM_PREFIX) }.forEach { room ->
            val elderUserId = room.removePrefix(ROOM_PREFIX)
            val hasSession = try {
                liveSessionRepository.find(elderUserId) != null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("실시간 영상 정리 — 세션 확인 실패로 건너뜀 room={}: {}", room, e.message)
                return@forEach
            }
            if (!hasSession && roomService.deleteRoom(room)) deleted++
        }
        if (deleted > 0) log.info("실시간 영상 정리 — 만료된 방 {}개 삭제", deleted)
    }
}
