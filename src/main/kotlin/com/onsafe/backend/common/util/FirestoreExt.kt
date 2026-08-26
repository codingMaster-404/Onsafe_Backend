package com.onsafe.backend.common.util

import com.google.api.core.ApiFuture
import com.google.cloud.Timestamp
import com.google.cloud.firestore.DocumentReference
import com.google.cloud.firestore.Firestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Date

suspend fun <T> ApiFuture<T>.await(): T = withContext(Dispatchers.IO) { get() }

// Firestore WriteBatch는 최대 500건까지 허용해 청크로 나눠 커밋한다. 여러 Repository의
// "회원탈퇴 시 연쇄 삭제" 로직이 문서당 순차 단건 삭제 대신 이 헬퍼로 일괄 삭제하도록 공유한다.
suspend fun Firestore.deleteInBatches(refs: List<DocumentReference>) {
    refs.chunked(500).forEach { chunk ->
        val batch = batch()
        chunk.forEach { batch.delete(it) }
        batch.commit().await()
    }
}

fun LocalDateTime.toTimestamp(): Timestamp =
    Timestamp.of(Date.from(atZone(ZoneId.systemDefault()).toInstant()))

fun Timestamp.toLocalDateTime(): LocalDateTime =
    toDate().toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime()
