package com.onsafe.backend.domain.internal.service

import com.onsafe.backend.common.util.ExpiryBackfillResult
import com.onsafe.backend.domain.logs.repository.FallLogRepository
import com.onsafe.backend.domain.notification.repository.NotificationRepository
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

/**
 * 보관 기간 백필 잡 — expired_at(Firestore TTL 대상 필드) 도입 전에 저장된 낙상 로그·알림에 만료 시각을 채운다.
 *
 * 새 문서는 저장 시 expired_at 이 기록되지만(FallLog·Notification.RETENTION_PERIOD), 기존 문서는 필드가 없어
 * TTL 정책이 지우지 못한다. 이 잡이 `기준 시각 + 보관 기간`을 채우면 이미 기간이 지난 문서는 TTL 이 24시간 안에 지운다.
 *
 * 안전 스위치 `retention.backfill-enabled`(기본 false): 꺼져 있으면 아무것도 쓰지 않고 대상·만료 건수만 로그로 남긴다.
 * 운영에서 건수를 확인한 뒤 켠다 — 켜는 순간 30일·7일 지난 데이터가 일괄 삭제 대상이 된다.
 *
 * Cloud Scheduler 가 매일 `POST /internal/jobs/retention-backfill` 로 트리거한다. 한 번에 컬렉션당 [BATCH_LIMIT]
 * 건까지만 기록하고 남은 문서는 다음 실행에서 이어서 처리한다. 대상이 0건으로 유지되면 후속 PR 에서 잡을 제거한다.
 */
@Service
class RetentionBackfillJob(
    private val fallLogRepository: FallLogRepository,
    private val notificationRepository: NotificationRepository,
    @Value("\${retention.backfill-enabled:false}") private val enabled: Boolean
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        private const val BATCH_LIMIT = 1000
    }

    suspend fun run() {
        // 한 컬렉션이 실패해도 다른 컬렉션은 진행한다 — 결과는 로그로만 남긴다(fire-and-forget).
        backfill("fall_logs") { fallLogRepository.backfillExpiredAt(BATCH_LIMIT, enabled) }
        backfill("notifications") { notificationRepository.backfillExpiredAt(BATCH_LIMIT, enabled) }
    }

    private suspend fun backfill(collection: String, block: suspend () -> ExpiryBackfillResult) {
        val result = try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error("보관 기간 백필 실패 — collection={}: {}", collection, e.message, e)
            return
        }
        if (result.missing == 0 && result.skipped == 0) return
        log.info(
            "보관 기간 백필 {} — collection={}, 필드 없음 {}건(이미 만료 {}건), 기록 {}건, 기준 필드 없음 {}건",
            if (enabled) "완료" else "(스위치 꺼짐 — 건수만 확인)",
            collection, result.missing, result.alreadyExpired, result.updated, result.skipped
        )
        if (result.missing == BATCH_LIMIT) {
            log.warn("백필 대상이 배치 상한({})에 도달 — collection={}, 다음 실행에서 이어서 처리", BATCH_LIMIT, collection)
        }
    }
}
