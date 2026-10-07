package com.onsafe.backend.domain.notification

import com.google.api.core.ApiFutures
import com.google.cloud.Timestamp
import com.google.cloud.firestore.CollectionReference
import com.google.cloud.firestore.DocumentReference
import com.google.cloud.firestore.Firestore
import com.google.cloud.firestore.Query
import com.google.cloud.firestore.QuerySnapshot
import com.google.cloud.firestore.WriteResult
import com.onsafe.backend.common.util.toLocalDateTime
import com.onsafe.backend.domain.notification.model.entity.Notification
import com.onsafe.backend.domain.notification.repository.NotificationRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.LocalDateTime

class NotificationRepositoryTest {

    private val firestore: Firestore = mockk()
    private val col: CollectionReference = mockk()
    private val docRef: DocumentReference = mockk()
    private val repository = NotificationRepository(firestore)

    init {
        every { firestore.collection("notifications") } returns col
    }

    @Test
    fun `저장 - expired_at 은 created_at + 7일 Timestamp 로 기록된다`() = runTest {
        val createdAt = LocalDateTime.of(2026, 10, 1, 12, 0)
        val notification = Notification(userId = "testUser", title = "낙상 감지", body = "확인해 주세요", createdAt = createdAt)
        val written = slot<Map<String, Any>>()
        every { col.document() } returns docRef
        every { docRef.id } returns "noti001"
        every { docRef.set(capture(written)) } returns ApiFutures.immediateFuture(mockk<WriteResult>())

        repository.save(notification)

        val expiredAt = written.captured["expired_at"] as Timestamp
        assertEquals(LocalDateTime.of(2026, 10, 8, 12, 0), expiredAt.toLocalDateTime())
    }

    @Test
    fun `목록 - 7일 이내 알림만 조회한다 (TTL 삭제 지연 보완)`() = runTest {
        val bound = slot<Any>()
        val query: Query = mockk()
        val snapshot: QuerySnapshot = mockk { every { documents } returns emptyList() }
        every { col.whereEqualTo("user_id", "testUser") } returns query
        every { query.whereGreaterThanOrEqualTo("created_at", capture(bound)) } returns query
        every { query.orderBy("created_at", Query.Direction.DESCENDING) } returns query
        every { query.limit(50) } returns query
        every { query.get() } returns ApiFutures.immediateFuture(snapshot)

        repository.findRecentByUserId("testUser")

        val diff = Duration.between(LocalDateTime.now().minusDays(7), (bound.captured as Timestamp).toLocalDateTime()).abs()
        assertTrue(diff < Duration.ofMinutes(1))
    }
}
