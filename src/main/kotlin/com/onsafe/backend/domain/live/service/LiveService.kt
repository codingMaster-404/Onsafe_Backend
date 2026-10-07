package com.onsafe.backend.domain.live.service

import com.onsafe.backend.common.exception.BusinessException
import com.onsafe.backend.common.exception.ErrorCode
import com.onsafe.backend.common.livekit.LiveKitTokenIssuer
import com.onsafe.backend.common.ratelimit.RateLimiter
import com.onsafe.backend.domain.auth.model.entity.LoginHistory
import com.onsafe.backend.domain.auth.model.entity.SecurityEventType
import com.onsafe.backend.domain.auth.repository.LoginHistoryRepository
import com.onsafe.backend.domain.camera.repository.RealtimeDataRepository
import com.onsafe.backend.domain.camera.service.HeartbeatWatchdogJob
import com.onsafe.backend.domain.guardian.repository.GuardianLinkRepository
import com.onsafe.backend.domain.live.model.dto.LiveTokenResponse
import com.onsafe.backend.domain.live.model.entity.LiveSession
import com.onsafe.backend.domain.live.repository.LiveSessionRepository
import com.onsafe.backend.domain.notification.service.NotificationService
import com.onsafe.backend.domain.settings.repository.SettingsRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 보호자 실시간 영상(LIVE) — 요청형 세션(V2). 피보호자 1명당 세션 1개, 최대 [SESSION_DURATION](V3).
 *
 * - 시작: 연결된 보호자 + 피보호자 영상 동의(W1) + (새 세션이면) 피보호자 카메라 온라인(heartbeat, W7)일 때만.
 *   진행 중이면 같은 세션에 들어가며 만료를 지금+5분으로 연장한다
 *   (앱의 "연장" 버튼도 같은 호출). 연장은 시작 요청 rate limit으로 상한을 둔다.
 * - 송출 요청: 세션을 **새로 만들 때만** 피보호자 기기에 FCM data 메시지(`event=live_request`)를 보낸다 — 연장 땐 이미 송출 중이다.
 *   메시지엔 토큰을 싣지 않는다(기기 알림 로그 등에 남지 않게). 앱은 받으면 송출 토큰을 따로 요청한다.
 * - 송출 토큰: 진행 중 세션이 있을 때만 피보호자에게 발급.
 * - 열람 기록: 시작·종료를 보안 이벤트로 남긴다(보호자 명의, 대상 = 피보호자).
 */
@Service
class LiveService(
    private val guardianLinkRepository: GuardianLinkRepository,
    private val settingsRepository: SettingsRepository,
    private val liveSessionRepository: LiveSessionRepository,
    private val tokenIssuer: LiveKitTokenIssuer,
    private val rateLimiter: RateLimiter,
    private val loginHistoryRepository: LoginHistoryRepository,
    private val notificationService: NotificationService,
    private val terminator: LiveSessionTerminator,
    private val realtimeDataRepository: RealtimeDataRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        val SESSION_DURATION: Duration = Duration.ofMinutes(5)
        private const val START_LIMIT_PER_HOUR = 20L
        const val EVENT_LIVE_REQUEST = "live_request"
    }

    suspend fun startSession(guardianUserId: String, elderUserId: String, now: Instant = Instant.now()): LiveTokenResponse {
        requireLinked(guardianUserId, elderUserId)
        rateLimiter.requireAllowed("rl:live-start:$guardianUserId", limit = START_LIMIT_PER_HOUR, windowSec = 3600)
        requireLiveVideoEnabled(elderUserId)

        val existing = liveSessionRepository.find(elderUserId)
        // 새 세션만 확인한다 — 연장은 이미 송출 중이라는 뜻이다.
        if (existing == null) requireDeviceOnline(elderUserId, now)
        val session = LiveSession(
            elderUserId = elderUserId,
            startedBy = existing?.startedBy ?: guardianUserId,
            expiresAt = now.plus(SESSION_DURATION),
        )
        liveSessionRepository.save(session, now)
        recordEvent(guardianUserId, elderUserId, SecurityEventType.LIVE_VIEW_START)
        val delivered = if (existing == null) requestPublish(session) else null

        return response(elderUserId, tokenIssuer.viewerToken(elderUserId, guardianUserId, SESSION_DURATION), session.expiresAt)
            .copy(requestDelivered = delivered)
    }

    // 끝난 세션에 다시 보내도 오류 없이 통과(멱등). 연결된 보호자 누구나 끝낼 수 있다 — 세션은 피보호자 단위다.
    // LiveKit 방까지 지워 이미 접속한 송출·시청도 끊는다.
    suspend fun endSession(guardianUserId: String, elderUserId: String) {
        requireLinked(guardianUserId, elderUserId)
        if (terminator.terminate(elderUserId, reason = "guardian_end")) {
            recordEvent(guardianUserId, elderUserId, SecurityEventType.LIVE_VIEW_END)
        }
    }

    suspend fun issuePublishToken(elderUserId: String, now: Instant = Instant.now()): LiveTokenResponse {
        val session = liveSessionRepository.find(elderUserId)
            ?.takeIf { it.expiresAt.isAfter(now) }
            ?: throw BusinessException(ErrorCode.LIVE_SESSION_NOT_FOUND)
        // 세션 시작 뒤 동의를 철회했으면 송출하지 않는다.
        requireLiveVideoEnabled(elderUserId)
        val remaining = Duration.between(now, session.expiresAt)
        return response(elderUserId, tokenIssuer.publisherToken(elderUserId, remaining), session.expiresAt)
    }

    // 전달 실패(토큰 없음·FCM 오류)가 시청 시작을 막지 않게 하되 결과를 돌려준다(W7) — false면 앱이 바로 안내하고,
    // true여도 LiveKit 참가자 입장을 짧게(약 30초) 기다린 뒤 안 오면 종료 API를 부른다(프론트). 실패 사실은 로그로 남긴다.
    private suspend fun requestPublish(session: LiveSession): Boolean {
        val data = mapOf(
            "event" to EVENT_LIVE_REQUEST,
            "room" to LiveKitTokenIssuer.roomName(session.elderUserId),
            "expires_at" to session.expiresAt.epochSecond.toString(),
        )
        return runCatching { notificationService.sendDataMessage(session.elderUserId, data, SESSION_DURATION) }
            .onSuccess { delivered ->
                if (delivered == 0) log.warn("실시간 영상 송출 요청 미전달(FCM 토큰 없음) — elder={}", session.elderUserId)
            }
            .onFailure { e -> log.warn("실시간 영상 송출 요청 실패 — elder={}: {}", session.elderUserId, e.message) }
            .map { it > 0 }
            .getOrDefault(false)
    }

    // 카메라 모드가 켜져 있어야 송출할 수 있으므로 heartbeat(2분 주기)가 가장 정확한 신호다.
    // 기준은 오프라인 알림과 같다(HeartbeatWatchdogJob.OFFLINE_THRESHOLD, 6분). heartbeat 기록이 없으면 오프라인.
    private suspend fun requireDeviceOnline(elderUserId: String, now: Instant) {
        val last = realtimeDataRepository.findByUserId(elderUserId)?.lastHeartbeatAt
            ?.atZone(ZoneId.systemDefault())?.toInstant()
        if (last == null || last.isBefore(now.minus(HeartbeatWatchdogJob.OFFLINE_THRESHOLD))) {
            throw BusinessException(ErrorCode.LIVE_DEVICE_OFFLINE)
        }
    }

    private suspend fun requireLinked(guardianUserId: String, elderUserId: String) {
        if (guardianUserId == elderUserId) throw BusinessException(ErrorCode.FORBIDDEN)
        guardianLinkRepository.find(guardianUserId, elderUserId) ?: throw BusinessException(ErrorCode.FORBIDDEN)
    }

    private suspend fun requireLiveVideoEnabled(elderUserId: String) {
        val enabled = settingsRepository.findByUserId(elderUserId)?.liveVideoEnabled ?: false
        if (!enabled) throw BusinessException(ErrorCode.LIVE_NOT_ALLOWED)
    }

    private fun response(elderUserId: String, token: String, expiresAt: Instant) = LiveTokenResponse(
        serverUrl = tokenIssuer.serverUrl,
        room = LiveKitTokenIssuer.roomName(elderUserId),
        token = token,
        expiresAt = LocalDateTime.ofInstant(expiresAt, ZoneId.systemDefault()),
    )

    // 기록 실패가 시청을 막지 않도록 격리하고 실패 사실만 남긴다(UserService.recordSecurityEvent와 같은 정책).
    private suspend fun recordEvent(guardianUserId: String, elderUserId: String, type: SecurityEventType) {
        runCatching {
            loginHistoryRepository.save(
                LoginHistory(historyId = "", userId = guardianUserId, eventType = type, targetUserId = elderUserId)
            )
        }.onFailure { e ->
            log.error("실시간 영상 열람 기록 실패 — guardian={}, elder={}, event={}: {}", guardianUserId, elderUserId, type, e.message, e)
        }
    }
}
