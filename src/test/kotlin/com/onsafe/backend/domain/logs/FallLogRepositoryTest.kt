package com.onsafe.backend.domain.logs

import com.google.api.core.ApiFutures
import com.google.cloud.Timestamp
import com.google.cloud.firestore.CollectionReference
import com.google.cloud.firestore.DocumentReference
import com.google.cloud.firestore.DocumentSnapshot
import com.google.cloud.firestore.Firestore
import com.google.cloud.firestore.Query
import com.google.cloud.firestore.QuerySnapshot
import com.google.cloud.firestore.WriteResult
import com.onsafe.backend.common.security.EncryptionService
import com.onsafe.backend.common.util.toLocalDateTime
import com.onsafe.backend.common.util.toTimestamp
import com.onsafe.backend.domain.logs.model.entity.FallLog
import com.onsafe.backend.domain.logs.repository.FallLogRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.LocalDateTime

class FallLogRepositoryTest {

    private val firestore: Firestore = mockk()
    private val encryptionService: EncryptionService = mockk()
    private val col: CollectionReference = mockk()
    private val docRef: DocumentReference = mockk()
    private val repository = FallLogRepository(firestore, encryptionService)

    init {
        every { firestore.collection("fall_logs") } returns col
    }

    @Test
    fun `저장 - expired_at 은 timestamp + 30일 Timestamp 로 기록된다`() = runTest {
        val timestamp = LocalDateTime.of(2026, 10, 1, 12, 0)
        val log = FallLog(logId = "log001", deviceId = "device1", userId = "testUser", score = 85f, fall = true, timestamp = timestamp)
        val written = slot<Map<String, Any>>()
        every { col.document("log001") } returns docRef
        every { docRef.set(capture(written)) } returns ApiFutures.immediateFuture(mockk<WriteResult>())

        repository.save(log)

        val expiredAt = written.captured["expired_at"] as Timestamp
        assertEquals(LocalDateTime.of(2026, 10, 31, 12, 0), expiredAt.toLocalDateTime())
    }

    // ── 보관 기간 조회 필터 (TTL 은 만료 후 최대 24시간 늦게 지운다) ─────────────

    // 목록 쿼리가 timestamp 하한으로 넘긴 값을 잡는다.
    private fun captureListLowerBound(): io.mockk.CapturingSlot<Any> {
        val bound = slot<Any>()
        val query: Query = mockk()
        val snapshot: QuerySnapshot = mockk { every { documents } returns emptyList() }
        every { col.whereEqualTo("user_id", "testUser") } returns query
        every { query.whereGreaterThanOrEqualTo("timestamp", capture(bound)) } returns query
        every { query.orderBy("timestamp", Query.Direction.DESCENDING) } returns query
        every { query.limit(100) } returns query
        every { query.get() } returns ApiFutures.immediateFuture(snapshot)
        return bound
    }

    private fun assertAbout(expected: LocalDateTime, actual: Any) {
        val diff = Duration.between(expected, (actual as Timestamp).toLocalDateTime()).abs()
        assertTrue(diff < Duration.ofMinutes(1), "expected≈$expected actual=${actual.toLocalDateTime()}")
    }

    @Test
    fun `목록 - 본인 조회는 30일 이내만 조회한다`() = runTest {
        val bound = captureListLowerBound()

        repository.findRecentByUserId("testUser")

        assertAbout(LocalDateTime.now().minusDays(30), bound.captured)
    }

    @Test
    fun `목록 - 보호자 연결 시각이 30일 이내면 그 시각부터 조회한다`() = runTest {
        val bound = captureListLowerBound()
        val since = LocalDateTime.now().minusDays(3).withNano(0)

        repository.findRecentByUserId("testUser", since = since)

        assertEquals(since.toTimestamp(), bound.captured)
    }

    @Test
    fun `카운트 - 보호자 연결 시각이 30일보다 오래됐으면 30일 이내만 센다`() = runTest {
        val bound = captureListLowerBound()

        repository.countByUserId("testUser", since = LocalDateTime.now().minusDays(90))

        assertAbout(LocalDateTime.now().minusDays(30), bound.captured)
    }

    private fun givenStoredLog(daysAgo: Long) {
        val doc: DocumentSnapshot = mockk(relaxed = true) {
            every { exists() } returns true
            every { id } returns "log001"
            every { getString("user_id") } returns "testUser"
            every { getString("video_url") } returns null
            every { getTimestamp("last_reminder_at") } returns null
            every { getTimestamp("timestamp") } returns LocalDateTime.now().minusDays(daysAgo).toTimestamp()
        }
        every { col.document("log001") } returns docRef
        every { docRef.get() } returns ApiFutures.immediateFuture(doc)
    }

    @Test
    fun `단건 - 30일 지난 로그는 TTL 삭제 전이라도 없는 것으로 본다`() = runTest {
        givenStoredLog(daysAgo = 31)

        assertNull(repository.findByLogIdAndUserId("log001", "testUser"))
    }

    @Test
    fun `단건 - 30일 이내 로그는 조회된다`() = runTest {
        givenStoredLog(daysAgo = 29)

        assertNotNull(repository.findByLogIdAndUserId("log001", "testUser"))
    }
}
