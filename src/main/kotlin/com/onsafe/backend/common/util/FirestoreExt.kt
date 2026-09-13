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

// primary 문서가 이미 존재하면 아무것도 쓰지 않고 false를 반환하고, 존재하지 않으면 primary와
// others를 같은 트랜잭션으로 함께 생성하고 true를 반환한다. 서로 다른 컬렉션에 걸친 "생성 시
// 함께 만들어져야 하는" 문서 묶음(예: 회원가입의 users + settings)을 부분 성공 없이 원자적으로
// 만들 때 쓴다. Firestore 트랜잭션 제약상 모든 읽기가 쓰기보다 먼저 와야 하므로 존재 확인을
// 가장 먼저 수행한다.
suspend fun Firestore.createAllIfNotExists(
    primary: DocumentReference,
    primaryData: Map<String, Any?>,
    vararg others: Pair<DocumentReference, Map<String, Any?>>
): Boolean = runTransaction { tx ->
    val exists = tx.get(primary).get().exists()
    if (!exists) {
        tx.set(primary, primaryData)
        others.forEach { (ref, data) -> tx.set(ref, data) }
    }
    !exists
}.await()

// docsToCheck에 지정한 문서들 중 하나라도 이미 존재하면 아무것도 쓰지 않고 false를 반환하고,
// 전부 없으면 docsToWrite를 모두 같은 트랜잭션으로 생성하고 true를 반환한다. 회원가입처럼
// "여러 축(userId/mail/phone)에서 각각 유일해야 하는" 요구를 트랜잭션으로 강제할 때 쓴다 —
// Firestore에는 SQL UNIQUE 제약이 없어 각 축마다 룩업 문서(user_emails/{mail} 등)를 별도로
// 두고 이 함수로 함께 검사/쓰기를 원자적으로 처리한다. Firestore 트랜잭션 제약상 모든 읽기가
// 쓰기보다 먼저 와야 하므로 존재 확인을 앞에 모아둔다.
suspend fun Firestore.createAllIfAllAbsent(
    docsToCheck: List<DocumentReference>,
    docsToWrite: List<Pair<DocumentReference, Map<String, Any?>>>
): Boolean = runTransaction { tx ->
    // tx.get을 forEach 안에서 순차 호출하면 각 read가 그 자리에서 blocking되므로 앞에서 모두
    // ApiFuture를 발급받고 뒤에서 .get()으로 순회 대기한다 — 트랜잭션 read 라운드트립을 병렬화.
    val futures = docsToCheck.map { tx.get(it) }
    val anyExists = futures.any { it.get().exists() }
    if (anyExists) return@runTransaction false

    docsToWrite.forEach { (ref, data) -> tx.set(ref, data) }
    true
}.await()

fun LocalDateTime.toTimestamp(): Timestamp =
    Timestamp.of(Date.from(atZone(ZoneId.systemDefault()).toInstant()))

fun Timestamp.toLocalDateTime(): LocalDateTime =
    toDate().toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime()
