package com.onsafe.backend.domain.camera.service

import com.onsafe.backend.domain.camera.repository.RealtimeDataRepository
import com.onsafe.backend.domain.guardian.repository.GuardianLinkRepository
import com.onsafe.backend.domain.notification.service.NotificationService
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.LocalDateTime

/**
 * 카메라 앱 오프라인 감지 워치독.
 *
 * 피보호자 앱이 2분마다 `POST /api/camera/heartbeat` 를 호출한다는 전제. 이 잡이 주기적으로
 * `last_heartbeat_at < now - OFFLINE_THRESHOLD` 인 활성 페어링을 스캔해 보호자에게 "카메라
 * 오프라인" FCM 을 발송한다. 스팸을 막기 위해 첫 감지 시 1회만 알리고, 복구 시 1회 더 알린 뒤,
 * 다시 오프라인이 되기 전까지는 재발송하지 않는다.
 *
 * Cloud Run min-instances=0 환경에서는 앱 내부 스케줄러(`@Scheduled`)가 트래픽 없는 시간에
 * 발동되지 않으므로, Cloud Scheduler 가 `POST /internal/jobs/heartbeat-watchdog` 를 5분 주기로
 * 트리거해 이 잡을 실행한다.
 */
@Component
class HeartbeatWatchdogJob(
    private val realtimeDataRepository: RealtimeDataRepository,
    private val guardianLinkRepository: GuardianLinkRepository,
    private val notificationService: NotificationService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        // Heartbeat 주기 2분 × 3회 미수신을 오프라인으로 판정. 짧으면 일시적 네트워크 끊김에도
        // 오탐이 잦고, 길면 진짜 위험 상황 감지가 지연된다.
        private val OFFLINE_THRESHOLD = Duration.ofMinutes(6)
        private const val SCAN_LIMIT = 500
    }

    suspend fun run() {
        val now = LocalDateTime.now()
        val cutoff = now.minus(OFFLINE_THRESHOLD)
        val candidates = runCatching { realtimeDataRepository.findOfflineCandidates(cutoff, SCAN_LIMIT) }
            .getOrElse { e ->
                log.error("오프라인 후보 조회 실패 cutoff=$cutoff: ${e.message}", e)
                return
            }
        if (candidates.isEmpty()) return

        // 후보별 알림 발송은 서로 독립적 — 하나가 실패해도 나머지는 계속. runCatching 으로
        // 격리하지 않으면 coroutineScope 가 형제 코루틴을 취소해 부분 처리로 남는다.
        coroutineScope {
            candidates.map { candidate ->
                async {
                    runCatching {
                        // 재알림 스팸 방지: 이미 오프라인 알림을 보냈으면 스킵. 복구 감지 및 재알림
                        // 게이트 초기화는 upsertHeartbeat 이후 별도 로직에서 담당 (아직 미구현).
                        if (candidate.lastOfflineNotifiedAt != null) return@runCatching
                        notificationService.notifyGuardiansCameraOffline(candidate.userId)
                        realtimeDataRepository.markOffline(candidate.userId, now)
                    }.onFailure { e ->
                        log.warn(
                            "오프라인 알림 처리 실패 — userId={}, cause={}",
                            candidate.userId, e.javaClass.simpleName
                        )
                    }
                }
            }.awaitAll()
        }

        log.info("heartbeat 워치독 완료: 후보 ${candidates.size}건 처리 (cutoff=$cutoff)")
    }
}