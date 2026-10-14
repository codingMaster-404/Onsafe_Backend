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
 *
 * 앱이 살아 있어도 WS 프레임이 안 들어오면(연결 실패로 멈춤·촬영 미시작) 낙상 감지는 멈춘 상태다.
 * heartbeat 는 카메라 화면이 열려 있으면 WS 와 무관하게 오므로, 같은 실행에서 Python 이 남기는
 * 프레임 수신 신호([StreamAliveStore])로 "감지 중단"을 따로 판정한다(카메라 스트리밍 B5).
 */
@Component
class HeartbeatWatchdogJob(
    private val realtimeDataRepository: RealtimeDataRepository,
    private val guardianLinkRepository: GuardianLinkRepository,
    private val notificationService: NotificationService,
    private val streamAliveStore: StreamAliveStore,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        // Heartbeat 주기 2분 × 3회 미수신을 오프라인으로 판정. 짧으면 일시적 네트워크 끊김에도
        // 오탐이 잦고, 길면 진짜 위험 상황 감지가 지연된다.
        // 실시간 영상(LIVE) 시작 전 기기 확인도 같은 기준을 쓴다(LiveService) — 바꾸면 두 곳이 함께 바뀐다.
        val OFFLINE_THRESHOLD: Duration = Duration.ofMinutes(6)
        private const val SCAN_LIMIT = 500
    }

    suspend fun run() {
        val now = LocalDateTime.now()
        val cutoff = now.minus(OFFLINE_THRESHOLD)
        checkOffline(now, cutoff)
        checkStreamDown(now, cutoff)
    }

    private suspend fun checkOffline(now: LocalDateTime, cutoff: LocalDateTime) {
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
                        // 재알림 스팸 방지: 이미 오프라인 알림을 보냈으면 스킵. 복구 알림과 재알림 게이트
                        // 초기화는 heartbeat 수신 시 CameraService.recordHeartbeat·upsertHeartbeat 가 담당.
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

    // 앱은 살아 있는데(heartbeat 가 cutoff 이후) 프레임 수신 신호가 없는 사용자를 찾는다.
    // 처음 발견하면 시각만 기록하고, 다음 실행(약 5분 뒤)에도 그대로면 보호자에게 1회 알린다 —
    // 카메라 화면을 열고 촬영을 누르기 전이나 재연결 중인 몇 초에 알림이 나가지 않게 하려는 것.
    // 신호가 돌아오면 알림을 보냈던 경우에만 복구 알림을 보내고 상태를 비운다.
    private suspend fun checkStreamDown(now: LocalDateTime, cutoff: LocalDateTime) {
        val candidates = runCatching { realtimeDataRepository.findOnlineCandidates(cutoff, SCAN_LIMIT) }
            .getOrElse { e ->
                log.error("감지 중단 후보 조회 실패 cutoff=$cutoff: ${e.message}", e)
                return
            }
        if (candidates.isEmpty()) return

        // Redis 를 못 읽으면 이번 실행은 건너뛴다 — 모두 "신호 없음"으로 보면 전원에게 오알림이 나간다.
        val alive = runCatching { streamAliveStore.aliveUserIds(candidates.map { it.userId }) }
            .getOrElse { e ->
                log.warn("스트리밍 생존 키 조회 실패 — 감지 중단 검사 건너뜀: ${e.message}")
                return
            }

        coroutineScope {
            candidates.map { candidate ->
                async {
                    runCatching {
                        val userId = candidate.userId
                        when {
                            userId in alive -> {
                                if (candidate.lastStreamDownNotifiedAt != null) {
                                    notificationService.notifyGuardiansStreamRecovered(userId)
                                }
                                if (candidate.streamDownDetectedAt != null || candidate.lastStreamDownNotifiedAt != null) {
                                    realtimeDataRepository.updateStreamDown(userId, detectedAt = null, notifiedAt = null)
                                }
                            }
                            candidate.lastStreamDownNotifiedAt != null -> Unit  // 이미 알림 — 복구 전까지 재발송 안 함
                            candidate.streamDownDetectedAt == null ->
                                realtimeDataRepository.updateStreamDown(userId, detectedAt = now, notifiedAt = null)
                            else -> {
                                notificationService.notifyGuardiansStreamDown(userId)
                                realtimeDataRepository.updateStreamDown(
                                    userId, detectedAt = candidate.streamDownDetectedAt, notifiedAt = now
                                )
                            }
                        }
                    }.onFailure { e ->
                        log.warn(
                            "감지 중단 처리 실패 — userId={}, cause={}",
                            candidate.userId, e.javaClass.simpleName
                        )
                    }
                }
            }.awaitAll()
        }
    }
}