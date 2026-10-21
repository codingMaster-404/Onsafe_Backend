package com.onsafe.backend.domain.camera

import com.google.api.core.ApiFutures
import com.google.cloud.Timestamp
import com.google.cloud.firestore.CollectionReference
import com.google.cloud.firestore.DocumentReference
import com.google.cloud.firestore.DocumentSnapshot
import com.google.cloud.firestore.Firestore
import com.google.cloud.firestore.SetOptions
import com.google.cloud.firestore.WriteResult
import com.onsafe.backend.common.util.toLocalDateTime
import com.onsafe.backend.common.util.toTimestamp
import com.onsafe.backend.domain.camera.repository.RealtimeDataRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

// heartbeat가 AI 점수·워치독 필드를 덮어쓰지 않는지(M6) 확인한다.
class RealtimeDataRepositoryTest {

    private val firestore: Firestore = mockk()
    private val col: CollectionReference = mockk()
    private val docRef: DocumentReference = mockk()
    private val snapshot: DocumentSnapshot = mockk()
    private val repository = RealtimeDataRepository(firestore)

    private val written = slot<Map<String, Any?>>()
    private val options = slot<SetOptions>()

    init {
        every { firestore.collection("realtime_data") } returns col
        every { col.document("testUser") } returns docRef
        every { docRef.get() } returns ApiFutures.immediateFuture(snapshot)
        every { docRef.set(capture(written), capture(options)) } returns ApiFutures.immediateFuture(mockk<WriteResult>())
    }

    private fun stubExisting(lastOfflineNotifiedAt: LocalDateTime?) {
        every { snapshot.exists() } returns true
        every { snapshot.id } returns "testUser"
        every { snapshot.getDouble("score") } returns 72.0
        every { snapshot.getString("level") } returns "주의"
        every { snapshot.getTimestamp(any()) } returns null
        every { snapshot.getTimestamp("updated_at") } returns LocalDateTime.of(2026, 10, 21, 9, 0).toTimestamp()
        every { snapshot.getTimestamp("last_offline_notified_at") } returns lastOfflineNotifiedAt?.toTimestamp()
        every { snapshot.getBoolean(any()) } returns null
        every { snapshot.getBoolean("is_online") } returns false
    }

    @Test
    fun `heartbeat는 heartbeat 4필드만 merge로 쓴다`() = runTest {
        stubExisting(lastOfflineNotifiedAt = null)
        val now = LocalDateTime.of(2026, 10, 21, 10, 0)

        repository.upsertHeartbeat("testUser", powerSaveMode = true, timestamp = now)

        assertEquals(SetOptions.merge(), options.captured)
        assertEquals(
            setOf("last_heartbeat_at", "is_online", "power_save_mode", "last_offline_notified_at"),
            written.captured.keys,
        )
        assertEquals(now, (written.captured["last_heartbeat_at"] as Timestamp).toLocalDateTime())
        assertEquals(true, written.captured["is_online"])
        assertEquals(true, written.captured["power_save_mode"])
        assertNull(written.captured["last_offline_notified_at"])
    }

    @Test
    fun `heartbeat는 쓰기 전 이전 상태를 돌려준다 - 복구 판정용`() = runTest {
        val notifiedAt = LocalDateTime.of(2026, 10, 21, 9, 30)
        stubExisting(lastOfflineNotifiedAt = notifiedAt)

        val previous = repository.upsertHeartbeat("testUser", powerSaveMode = false)

        assertEquals(notifiedAt, previous?.lastOfflineNotifiedAt)
        assertEquals(72f, previous?.score)
        assertEquals(false, previous?.isOnline)
    }

    @Test
    fun `문서가 없으면 null을 돌려주고 같은 4필드로 만든다`() = runTest {
        every { snapshot.exists() } returns false

        val previous = repository.upsertHeartbeat("testUser", powerSaveMode = false)

        assertNull(previous)
        assertEquals(SetOptions.merge(), options.captured)
        assertEquals(
            setOf("last_heartbeat_at", "is_online", "power_save_mode", "last_offline_notified_at"),
            written.captured.keys,
        )
    }
}
