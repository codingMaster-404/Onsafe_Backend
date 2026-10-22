package com.onsafe.backend.domain.notification

import com.google.api.core.ApiFutures
import com.google.cloud.Timestamp
import com.google.cloud.firestore.CollectionReference
import com.google.cloud.firestore.DocumentReference
import com.google.cloud.firestore.Firestore
import com.google.cloud.firestore.Query
import com.google.cloud.firestore.QueryDocumentSnapshot
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
    fun `저장·조회 - event를 기록하고 읽는다`() = runTest {
        val written = slot<Map<String, Any?>>()
        every { col.document() } returns docRef
        every { docRef.id } returns "noti001"
        every { docRef.set(capture(written)) } returns ApiFutures.immediateFuture(mockk<WriteResult>())

        repository.save(Notification(userId = "g1", title = "t", body = "b", subjectUserId = "elder1", event = "camera_offline"))

        assertEquals("camera_offline", written.captured["event"])

        val doc: QueryDocumentSnapshot = mockk(relaxed = true) {
            every { id } returns "noti001"
            every { getString(any()) } returns null
            every { getString("user_id") } returns "g1"
            every { getString("subject_user_id") } returns "elder1"
            every { getString("event") } returns "camera_offline"
            every { getDouble(any()) } returns null
            every { getBoolean(any()) } returns null
            every { getTimestamp(any()) } returns null
        }
        val query: Query = mockk()
        every { col.whereEqualTo("user_id", "g1") } returns query
        every { query.whereGreaterThanOrEqualTo("created_at", any()) } returns query
        every { query.orderBy("created_at", Query.Direction.DESCENDING) } returns query
        every { query.limit(50) } returns query
        every { query.get() } returns ApiFutures.immediateFuture(mockk<QuerySnapshot> { every { documents } returns listOf(doc) })

        val read = repository.findRecentByUserId("g1").single()

        assertEquals("camera_offline", read.event)
        assertEquals("elder1", read.subjectUserId)
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
