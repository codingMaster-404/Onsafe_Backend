package com.onsafe.backend.domain.auth.service

import com.onsafe.backend.domain.auth.repository.LoginHistoryRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.LocalDateTime

/**
 * 로그인 이력 보관 정책 — 90일 이상 오래된 항목 정리.
 *
 * 감사·부정접근 대응은 대부분 30~90일 내에 이뤄지므로 90일이면 실무상 충분하고,
 * 무한 누적으로 인한 Firestore 스토리지·읽기 비용 증가를 방지한다.
 * 회원탈퇴 시에는 [LoginHistoryRepository.deleteByUserId] 로 즉시 정리되므로 별개 경로.
 *
 * Cloud Run min-instances=0 환경에서는 `@Scheduled` 가 프로세스 부재 시간대(새벽 3시)에
 * 발동되지 않으므로, 앱 내부 스케줄러 대신 Cloud Scheduler 가 매일 03:00 KST 에
 * `POST /internal/jobs/login-history-cleanup` 을 트리거해 이 함수를 호출한다.
 *
 * Repository는 한 회 실행당 최대 [BATCH_LIMIT] 건까지만 삭제하고, 잔여분은 다음 실행에서 처리된다.
 */
@Component
class LoginHistoryCleanupJob(
    private val loginHistoryRepository: LoginHistoryRepository
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        private val RETENTION_PERIOD = Duration.ofDays(90)
        private const val BATCH_LIMIT = 1000
    }

    // Cloud Scheduler 트리거 진입점. 결과는 응답 본문이 아니라 로그로만 남긴다 —
    // 스케줄러 호출은 결과 데이터를 소비하지 않는 fire-and-forget 성격.
    suspend fun run() {
        val cutoff = LocalDateTime.now().minus(RETENTION_PERIOD)
        val deleted = runCatching { loginHistoryRepository.deleteOlderThan(cutoff, BATCH_LIMIT) }
            .getOrElse { e ->
                log.error("로그인 이력 정리 실패 cutoff=$cutoff: ${e.message}", e)
                return
            }
        if (deleted == 0) return
        log.info("로그인 이력 정리 완료: ${deleted}건 삭제 (cutoff=$cutoff)")
        if (deleted == BATCH_LIMIT) {
            log.warn("삭제 대상이 배치 상한(${BATCH_LIMIT})에 도달 — 다음 실행에서 이어서 처리")
        }
    }
}