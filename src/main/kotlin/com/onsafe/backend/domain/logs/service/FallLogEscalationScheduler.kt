package com.onsafe.backend.domain.logs.service

import com.onsafe.backend.domain.logs.repository.FallLogRepository
import com.onsafe.backend.domain.notification.service.NotificationService
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.LocalDateTime

/**
 * 미확인 위험(위험) 이벤트 에스컬레이션 — 확인(is_confirmed)될 때까지 REMINDER_INTERVAL마다 재알림.
 * 사용자당 가장 최근 이벤트만 대상으로 삼아, 6시간 danger_cd로 새 이벤트가 계속 생겨도
 * 리마인더가 중복으로 쌓이지 않게 한다(오래된 이벤트는 새 이벤트가 그 자리를 대체).
 *
 * 발송 직전 [FallLogRepository.claimReminder]가 트랜잭션으로 재확인하므로, 이 스케줄러의
 * 최초 조회 결과와 실제 발송 사이에 보호자가 확인했거나 다른 실행이 먼저 처리했어도 중복 발송되지 않는다.
 */
@Component
class FallLogEscalationScheduler(
    private val fallLogRepository: FallLogRepository,
    private val notificationService: NotificationService
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        private val REMINDER_INTERVAL = Duration.ofMinutes(15)  // 확인될 때까지 반복 주기
        private val LOOKBACK_WINDOW = Duration.ofHours(48)      // 조회 대상 상한 — 무한 누적 방지
    }

    // fixedDelay: 이전 실행이 끝난 뒤에만 다음 실행을 예약 — 겹침(중복 실행) 방지
    @Scheduled(fixedDelay = 5 * 60 * 1000L, initialDelay = 60 * 1000L)
    fun checkEscalations() = runBlocking {
        val cutoff = LocalDateTime.now().minus(LOOKBACK_WINDOW)
        val candidates = runCatching { fallLogRepository.findUnconfirmedDangerLogs(cutoff) }
            .getOrElse { e ->
                log.error("에스컬레이션 대상 조회 실패: ${e.message}", e)
                return@runBlocking
            }
        if (candidates.isEmpty()) return@runBlocking

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
                    data = mapOf("log_id" to claimed.logId, "user_id" to claimed.userId)
                )
            }.onFailure { e ->
                log.error("에스컬레이션 알림 전송 실패 log_id=${claimed.logId}: ${e.message}", e)
            }
        }
    }
}
