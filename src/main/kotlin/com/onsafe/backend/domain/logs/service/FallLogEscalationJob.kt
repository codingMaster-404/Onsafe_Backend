package com.onsafe.backend.domain.logs.service

import com.onsafe.backend.domain.camera.repository.RealtimeDataRepository
import com.onsafe.backend.domain.camera.service.HeartbeatWatchdogJob
import com.onsafe.backend.domain.logs.repository.FallLogRepository
import com.onsafe.backend.domain.notification.service.NotificationService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.LocalDateTime

/**
 * 미확인 위험(위험) 이벤트 에스컬레이션 — 확인(is_confirmed)될 때까지 REMINDER_INTERVAL마다 재알림.
 * 사용자당 가장 최근 이벤트만 대상으로 삼아, 6시간 danger_cd로 새 이벤트가 계속 생겨도
 * 리마인더가 중복으로 쌓이지 않게 한다(오래된 이벤트는 새 이벤트가 그 자리를 대체).
 *
 * 발송 직전 [FallLogRepository.claimReminder]가 트랜잭션으로 재확인하므로, 이 잡의
 * 최초 조회 결과와 실제 발송 사이에 보호자가 확인했거나 다른 실행이 먼저 처리했어도 중복 발송되지 않는다.
 *
 * Cloud Scheduler가 5분마다 `POST /internal/jobs/fall-escalation`으로 호출한다(E6-1). 이전에는 앱 내부
 * `@Scheduled`로만 돌아 Cloud Run min-instances=0에서 요청이 없는 시간에는 재알림이 나가지 않았다(다른 내부 잡과 같은 이유).
 * Cloud Scheduler는 이전 실행이 끝나기 전에도 다음 트리거를 보내지만, 겹쳐 실행돼도 위 트랜잭션이 중복 발송을 막는다.
 */
@Component
class FallLogEscalationJob(
    private val fallLogRepository: FallLogRepository,
    private val notificationService: NotificationService,
    private val realtimeDataRepository: RealtimeDataRepository
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        private val REMINDER_INTERVAL = Duration.ofMinutes(15)  // 확인될 때까지 반복 주기
        // 조회 대상 상한 — 무한 누적 방지. 해제 전 확인(GuardianService.getUnpairCheck)도 같은 기간을 센다.
        val LOOKBACK_WINDOW: Duration = Duration.ofHours(48)
        const val OFFLINE_GUARDIAN_BODY = "카메라가 오프라인이라 직접 확인이 어려울 수 있습니다. 피보호자에게 연락해 확인해주세요."
    }

    suspend fun run() {
        val cutoff = LocalDateTime.now().minus(LOOKBACK_WINDOW)
        val candidates = runCatching { fallLogRepository.findUnconfirmedDangerLogs(cutoff) }
            .getOrElse { e ->
                log.error("에스컬레이션 대상 조회 실패: ${e.message}", e)
                return
            }
        if (candidates.isEmpty()) return

        // 사용자당 가장 최근 이벤트만 에스컬레이션 대상으로 남긴다
        val latestPerUser = candidates.groupBy { it.userId }
            .mapValues { (_, logs) -> logs.maxBy { it.timestamp } }

        for (candidate in latestPerUser.values) {
            val claimed = runCatching { fallLogRepository.claimReminder(candidate.logId, REMINDER_INTERVAL) }
                .getOrElse { e ->
                    log.error("에스컬레이션 클레임 실패 log_id=${candidate.logId}: ${e.message}", e)
                    null
                } ?: continue

            runCatching {
                notificationService.notifyElderAndGuardians(
                    elderUserId = claimed.userId,
                    title = "확인이 필요합니다",
                    body = "이전 위험 알림이 아직 확인되지 않았습니다. 확인해주세요.",
                    logId = claimed.logId,
                    score = claimed.score,
                    fall = claimed.fall,
                    data = mapOf(
                        "event" to "fall_escalated",
                        "log_id" to claimed.logId,
                        "user_id" to claimed.userId
                    ),
                    guardianBody = if (isCameraOffline(claimed.userId)) OFFLINE_GUARDIAN_BODY else null
                )
            }.onFailure { e ->
                log.error("에스컬레이션 알림 전송 실패 log_id=${claimed.logId}: ${e.message}", e)
            }
        }
    }

    // 카메라 기기가 꺼져 있으면 피보호자가 앱에서 확인할 수 없으니 보호자에게 직접 연락을 안내한다(S6).
    // 기준은 LIVE 시작 전 기기 확인(LiveService.requireDeviceOnline)과 같다 — heartbeat 6분 초과 또는 기록 없음.
    // 워치독의 is_online 플래그는 최대 5분 늦게 바뀌어 쓰지 않는다. 조회 실패는 기존 문구로 보낸다(재알림을 막지 않음).
    private suspend fun isCameraOffline(elderUserId: String): Boolean {
        val data = runCatching { realtimeDataRepository.findByUserId(elderUserId) }
            .onFailure { e -> log.warn("재알림 카메라 상태 조회 실패 — elder=$elderUserId: ${e.message}") }
            .getOrElse { return false }
        val last = data?.lastHeartbeatAt
        return last == null || last.isBefore(LocalDateTime.now().minus(HeartbeatWatchdogJob.OFFLINE_THRESHOLD))
    }
}