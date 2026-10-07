package com.onsafe.backend.common.util

import com.google.api.core.ApiFutures
import com.google.cloud.Timestamp
import com.google.cloud.firestore.CollectionReference
import com.google.cloud.firestore.DocumentReference
import com.google.cloud.firestore.Firestore
import com.google.cloud.firestore.Query
import com.google.cloud.firestore.QueryDocumentSnapshot
import com.google.cloud.firestore.QuerySnapshot
import com.google.cloud.firestore.WriteBatch
import com.google.cloud.firestore.WriteResult
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.LocalDateTime

/**
 * TTL 정책은 expired_at 이 있는 문서만 지우므로, 필드 도입 전 문서에 `기준 시각 + 보관 기간`을 채우는 헬퍼.
 * 필드 없는 문서만 고르고, 스위치가 꺼져 있으면 쓰지 않으며, 상한을 지키고, 페이지를 넘겨 이어서 스캔하는지 확인한다.
 */
class ExpiryBackfillTest {

    private val firestore: Firestore = mockk()
    private val col: CollectionReference = mockk()
    private val query: Query = mockk()
    private val batch: WriteBatch = mockk()
    private val now = LocalDateTime.of(2026, 10, 1, 12, 0)
    private val period = Duration.ofDays(30)

    private val refs = mutableMapOf<String, DocumentReference>()

    // verify 블록 안에서 doc.reference 를 부르면 mockk 가 그 호출까지 검증 대상으로 기록하므로 참조를 따로 보관한다.
    private fun doc(id: String, hasExpiredAt: Boolean, timestamp: LocalDateTime?): QueryDocumentSnapshot {
        val ref: DocumentReference = mockk(name = id)
        refs[id] = ref
        return mockk {
            every { contains("expired_at") } returns hasExpiredAt
            every { getTimestamp("timestamp") } returns timestamp?.toTimestamp()
            every { reference } returns ref
        }
    }

    // 이미 채워진 문서 1 / 40일 전(만료) 1 / 1일 전 1 / 기준 필드 없음 1 — 한 페이지(500 미만)라 스캔은 1회
    private val filled = doc("filled", hasExpiredAt = true, timestamp = now.minusDays(2))
    private val old = doc("old", hasExpiredAt = false, timestamp = now.minusDays(40))
    private val recent = doc("recent", hasExpiredAt = false, timestamp = now.minusDays(1))
    private val noBase = doc("noBase", hasExpiredAt = false, timestamp = null)

    private fun givenDocs(vararg docs: QueryDocumentSnapshot) {
        val snapshot: QuerySnapshot = mockk { every { documents } returns docs.toList() }
        every { col.orderBy(any<com.google.cloud.firestore.FieldPath>()) } returns query
        every { query.limit(500) } returns query
        every { query.get() } returns ApiFutures.immediateFuture(snapshot)
        every { firestore.batch() } returns batch
        every { batch.update(any<DocumentReference>(), any<String>(), any()) } returns batch
        every { batch.commit() } returns ApiFutures.immediateFuture(listOf(mockk<WriteResult>()))
    }

    @Test
    fun `스위치 off - 필드 없는 문서만 세고 아무것도 쓰지 않는다`() = runTest {
        givenDocs(filled, old, recent, noBase)

        val result = firestore.backfillExpiredAt(col, "timestamp", period, limit = 1000, apply = false, now = now)

        assertEquals(ExpiryBackfillResult(missing = 2, alreadyExpired = 1, skipped = 1, updated = 0), result)
        verify(exactly = 0) { firestore.batch() }
    }

    @Test
    fun `스위치 on - 기준 시각 + 보관 기간을 Timestamp 로 기록한다`() = runTest {
        givenDocs(filled, old, recent, noBase)

        val result = firestore.backfillExpiredAt(col, "timestamp", period, limit = 1000, apply = true, now = now)

        assertEquals(ExpiryBackfillResult(missing = 2, alreadyExpired = 1, skipped = 1, updated = 2), result)
        verify(exactly = 1) { batch.update(refs.getValue("old"), "expired_at", now.minusDays(10).toTimestamp()) }
        verify(exactly = 1) { batch.update(refs.getValue("recent"), "expired_at", now.plusDays(29).toTimestamp()) }
        verify(exactly = 0) { batch.update(refs.getValue("filled"), any<String>(), any()) }
        verify(exactly = 1) { batch.commit() }
    }

    @Test
    fun `한 번에 상한 건수까지만 기록한다`() = runTest {
        givenDocs(old, recent)

        val result = firestore.backfillExpiredAt(col, "timestamp", period, limit = 1, apply = true, now = now)

        assertEquals(1, result.updated)
        verify(exactly = 1) { batch.update(refs.getValue("old"), "expired_at", any<Timestamp>()) }
        verify(exactly = 0) { batch.update(refs.getValue("recent"), any<String>(), any()) }
    }

    @Test
    fun `한 페이지(500건)가 가득 차면 마지막 문서 다음부터 이어서 스캔한다`() = runTest {
        // 1페이지: 이미 채워진 문서 500건(가득 참 → 다음 페이지 조회), 2페이지: 필드 없는 문서 2건(500 미만 → 종료)
        val firstPage = List(500) { i -> doc("filled-$i", hasExpiredAt = true, timestamp = now.minusDays(2)) }
        givenDocs(*firstPage.toTypedArray())
        val nextQuery: Query = mockk()
        val nextSnapshot: QuerySnapshot = mockk { every { documents } returns listOf(old, recent) }
        every { query.startAfter(firstPage.last()) } returns nextQuery
        every { nextQuery.get() } returns ApiFutures.immediateFuture(nextSnapshot)

        val result = firestore.backfillExpiredAt(col, "timestamp", period, limit = 1000, apply = true, now = now)

        assertEquals(ExpiryBackfillResult(missing = 2, alreadyExpired = 1, skipped = 0, updated = 2), result)
        verify(exactly = 1) { query.startAfter(firstPage.last()) }
        verify(exactly = 1) { batch.update(refs.getValue("old"), "expired_at", now.minusDays(10).toTimestamp()) }
        verify(exactly = 1) { batch.update(refs.getValue("recent"), "expired_at", now.plusDays(29).toTimestamp()) }
    }
}
