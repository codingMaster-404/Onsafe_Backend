package com.onsafe.backend.domain.internal

import com.onsafe.backend.common.util.ExpiryBackfillResult
import com.onsafe.backend.domain.internal.service.RetentionBackfillJob
import com.onsafe.backend.domain.logs.repository.FallLogRepository
import com.onsafe.backend.domain.notification.repository.NotificationRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * expired_at 도입 전 문서 백필 잡. 스위치 값이 그대로 Repository 에 전달되고,
 * 한 컬렉션이 실패해도 다른 컬렉션은 진행하는지 확인한다.
 */
class RetentionBackfillJobTest {

    private val fallLogRepository: FallLogRepository = mockk()
    private val notificationRepository: NotificationRepository = mockk()
    private val result = ExpiryBackfillResult(missing = 3, alreadyExpired = 2, skipped = 0, updated = 0)

    @Test
    fun `스위치 off - 두 컬렉션 모두 apply=false 로 건수만 확인한다`() = runTest {
        val job = RetentionBackfillJob(fallLogRepository, notificationRepository, enabled = false)
        coEvery { fallLogRepository.backfillExpiredAt(1000, false) } returns result
        coEvery { notificationRepository.backfillExpiredAt(1000, false) } returns result

        job.run()

        coVerify(exactly = 1) { fallLogRepository.backfillExpiredAt(1000, false) }
        coVerify(exactly = 1) { notificationRepository.backfillExpiredAt(1000, false) }
    }

    @Test
    fun `스위치 on - apply=true 로 기록한다`() = runTest {
        val job = RetentionBackfillJob(fallLogRepository, notificationRepository, enabled = true)
        coEvery { fallLogRepository.backfillExpiredAt(1000, true) } returns result.copy(updated = 3)
        coEvery { notificationRepository.backfillExpiredAt(1000, true) } returns result.copy(updated = 3)

        job.run()

        coVerify(exactly = 1) { fallLogRepository.backfillExpiredAt(1000, true) }
        coVerify(exactly = 1) { notificationRepository.backfillExpiredAt(1000, true) }
    }

    @Test
    fun `낙상 로그 백필이 실패해도 알림 백필은 진행한다`() = runTest {
        val job = RetentionBackfillJob(fallLogRepository, notificationRepository, enabled = true)
        coEvery { fallLogRepository.backfillExpiredAt(any(), any()) } throws RuntimeException("firestore down")
        coEvery { notificationRepository.backfillExpiredAt(1000, true) } returns result

        job.run()

        coVerify(exactly = 1) { notificationRepository.backfillExpiredAt(1000, true) }
    }
}
