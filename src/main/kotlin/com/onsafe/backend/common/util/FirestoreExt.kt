package com.onsafe.backend.common.util

import com.google.api.core.ApiFuture
import com.google.cloud.Timestamp
import com.google.cloud.firestore.CollectionReference
import com.google.cloud.firestore.DocumentReference
import com.google.cloud.firestore.DocumentSnapshot
import com.google.cloud.firestore.FieldPath
import com.google.cloud.firestore.Firestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Duration
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

// 백필 스캔 한 페이지 크기 — WriteBatch 상한(500)과 같게 둔다.
private const val BACKFILL_SCAN_PAGE = 500

/** [backfillExpiredAt] 결과. missing = 이번 실행에서 찾은 필드 없는 문서(상한까지), alreadyExpired = 그중 만료 시각이 이미 지난 문서. */
data class ExpiryBackfillResult(val missing: Int, val alreadyExpired: Int, val skipped: Int, val updated: Int)

// Firestore TTL은 expired_at 필드가 있는 문서만 지우므로, 필드 도입 전에 저장된 문서에 `기준 시각 + 보관 기간`을 채운다.
// 이미 기간이 지난 문서는 채우는 즉시 TTL 대상이 된다(보통 24시간 안 삭제). Firestore는 "필드 없음" 조건으로
// 쿼리할 수 없어 문서 ID 순으로 페이지 단위 스캔 후 인메모리에서 거른다. 한 번에 최대 limit 건만 기록하고
// 나머지는 다음 실행이 이어서 처리한다(이미 채운 문서는 건너뛰므로 재실행 안전). apply=false 면 건수만 센다.
// 기준 필드가 없는 문서는 만료 시각을 정할 수 없어 skipped 로 세고 건드리지 않는다.
suspend fun Firestore.backfillExpiredAt(
    collection: CollectionReference,
    baseField: String,
    period: Duration,
    limit: Int,
    apply: Boolean,
    now: LocalDateTime = LocalDateTime.now()
): ExpiryBackfillResult {
    val targets = mutableListOf<Pair<DocumentReference, LocalDateTime>>()
    var skipped = 0
    var last: DocumentSnapshot? = null
    while (targets.size < limit) {
        var query = collection.orderBy(FieldPath.documentId()).limit(BACKFILL_SCAN_PAGE)
        if (last != null) query = query.startAfter(last)
        val docs = query.get().await().documents
        for (doc in docs) {
            if (targets.size >= limit) break
            if (doc.contains(EXPIRED_AT_FIELD)) continue
            val base = doc.getTimestamp(baseField)?.toLocalDateTime()
            if (base == null) skipped++ else targets += doc.reference to base.plus(period)
        }
        if (docs.size < BACKFILL_SCAN_PAGE) break
        last = docs.last()
    }
    if (apply) {
        targets.chunked(BACKFILL_SCAN_PAGE).forEach { chunk ->
            val batch = batch()
            chunk.forEach { (ref, expiredAt) -> batch.update(ref, EXPIRED_AT_FIELD, expiredAt.toTimestamp()) }
            batch.commit().await()
        }
    }
    return ExpiryBackfillResult(
        missing = targets.size,
        alreadyExpired = targets.count { (_, expiredAt) -> expiredAt <= now },
        skipped = skipped,
        updated = if (apply) targets.size else 0
    )
}

// TTL 정책 대상 필드명 — 배포 워크플로(deploy-cloudrun.yml "Apply Firestore TTL policies")와 같은 이름이어야 한다.
const val EXPIRED_AT_FIELD = "expired_at"

fun LocalDateTime.toTimestamp(): Timestamp =
    Timestamp.of(Date.from(atZone(ZoneId.systemDefault()).toInstant()))

fun Timestamp.toLocalDateTime(): LocalDateTime =
    toDate().toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime()
