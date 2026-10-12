package com.onsafe.backend.domain.live.service

import com.onsafe.backend.common.livekit.LiveKitRoomService
import com.onsafe.backend.common.livekit.LiveKitTokenIssuer
import com.onsafe.backend.domain.live.repository.LiveSessionRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * 진행 중 실시간 영상을 즉시 끝낸다 — Redis 세션 삭제 + LiveKit 방 삭제(접속자 전원 퇴장).
 *
 * 보호자 종료 요청 외에 **권한이 사라지는 순간**에도 호출한다(W4): 연결 해제, 재페어링으로 밀려남, 탈퇴, 영상 동의 철회.
 * 세션은 피보호자 단위(1:1 정책)라 방을 통째로 지운다.
 *
 * 호출부의 주 작업(연결 해제·탈퇴 등)을 막지 않도록 **절대 예외를 던지지 않는다** — 실패는 로그로 남기고,
 * 남은 세션은 Redis TTL(최대 5분)로 사라진다.
 */
@Component
class LiveSessionTerminator(
    private val liveSessionRepository: LiveSessionRepository,
    private val roomService: LiveKitRoomService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** @return Redis 세션이 있어 지웠으면 true(종료 기록 여부 판단용). */
    suspend fun terminate(elderUserId: String, reason: String): Boolean {
        val deleted = runCatching { liveSessionRepository.delete(elderUserId) }
            .onFailure { e -> log.warn("실시간 영상 세션 삭제 실패 — elder={}, reason={}: {}", elderUserId, reason, e.message) }
            .getOrDefault(false)
        // 세션 키가 이미 없어도 방은 남아 있을 수 있어(만료 직후 등) 항상 지운다.
        runCatching { roomService.deleteRoom(LiveKitTokenIssuer.roomName(elderUserId)) }
            .onFailure { e -> log.warn("실시간 영상 방 삭제 실패 — elder={}, reason={}: {}", elderUserId, reason, e.message) }
        if (deleted) log.info("실시간 영상 종료 — elder={}, reason={}", elderUserId, reason)
        return deleted
    }
}
