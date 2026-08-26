package com.onsafe.backend.domain.logs.service

import com.onsafe.backend.common.storage.StorageService
import com.onsafe.backend.domain.logs.repository.FallLogRepository
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.LocalDateTime

/**
 * 직접 업로드(#14) + 완료 콜백(#15) 경로에서 콜백이 유실된 경우를 보정한다.
 * video_url이 비어있는 위험(위험) 레벨 로그를 주기적으로 훑어 GCS에 실제 객체가 있는지 재확인하고,
 * 있으면 video_url을 채운다. 아직 업로드 전이거나 영구 실패한 로그는 GRACE_PERIOD 이전이라 건너뛰거나
 * LOOKBACK_WINDOW를 넘겨 대상에서 자연히 빠진다(FallLogEscalationScheduler와 동일한 상한 방식).
 */
@Component
class FallLogVideoReconciliationJob(
    private val fallLogRepository: FallLogRepository,
    private val storageService: StorageService
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        private val GRACE_PERIOD = Duration.ofMinutes(15)   // signed URL TTL(10분) + 여유 — 이보다 최근 로그는 아직 업로드 중일 수 있어 건너뜀
        private val LOOKBACK_WINDOW = Duration.ofHours(48)  // 조회 대상 상한 — 무한 누적 방지
    }

    // fixedDelay: 이전 실행이 끝난 뒤에만 다음 실행을 예약 — 겹침(중복 실행) 방지
    @Scheduled(fixedDelay = 15 * 60 * 1000L, initialDelay = 90 * 1000L)
    fun reconcile() = runBlocking {
        val now = LocalDateTime.now()
        val graceCutoff = now.minus(GRACE_PERIOD)

        val candidates = runCatching { fallLogRepository.findMissingVideoLogs(now.minus(LOOKBACK_WINDOW)) }
            .getOrElse { e ->
                log.error("정합성 보정 대상 조회 실패: ${e.message}", e)
                return@runBlocking
            }
            .filter { it.timestamp <= graceCutoff }

        for (candidate in candidates) {
            val gcsPath = "fall-videos/${candidate.logId}.mp4"
            val exists = runCatching { storageService.blobExists(gcsPath) }
                .getOrElse { e ->
                    log.error("정합성 보정 GCS 확인 실패 log_id=${candidate.logId}: ${e.message}", e)
                    false
                }
            if (!exists) continue

            runCatching {
                fallLogRepository.setVideoUrlByLogIdAndUserId(candidate.logId, candidate.userId, gcsPath)
            }.onSuccess {
                log.info("정합성 보정 완료 log_id=${candidate.logId} — 콜백 유실분 video_url 반영")
            }.onFailure { e ->
                log.error("정합성 보정 video_url 갱신 실패 log_id=${candidate.logId}: ${e.message}", e)
            }
        }
    }
}
