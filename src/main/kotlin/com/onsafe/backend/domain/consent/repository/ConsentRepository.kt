package com.onsafe.backend.domain.consent.repository

import com.google.cloud.firestore.DocumentReference
import com.google.cloud.firestore.DocumentSnapshot
import com.google.cloud.firestore.Firestore
import com.onsafe.backend.common.util.await
import com.onsafe.backend.common.util.deleteInBatches
import com.onsafe.backend.common.util.toLocalDateTime
import com.onsafe.backend.common.util.toTimestamp
import com.onsafe.backend.domain.consent.model.entity.ConsentRecord
import com.onsafe.backend.domain.consent.model.entity.ConsentType
import org.springframework.stereotype.Repository
import java.time.LocalDateTime

@Repository
class ConsentRepository(private val firestore: Firestore) {

    private val col get() = firestore.collection("consents")

    // 이력이 여러 건 쌓일 수 있어(재동의 등) 항상 타입별 가장 최근 레코드만 골라 돌려준다.
    suspend fun findLatestByUserId(userId: String): Map<ConsentType, ConsentRecord> =
        col.whereEqualTo("user_id", userId).get().await().documents
            .map { it.toConsentRecord() }
            .groupBy { it.type }
            .mapValues { (_, records) -> records.maxBy { it.agreedAt } }

    suspend fun deleteByUserId(userId: String) {
        val docs = col.whereEqualTo("user_id", userId).get().await().documents
        firestore.deleteInBatches(docs.map { it.reference })
    }

    // UserRepository.createIfNotExists의 additionalWrites로 넘겨, 회원가입 시 users/settings
    // 문서와 같은 트랜잭션에서 원자적으로 생성되도록 (ref, data) 쌍만 만들어준다 — 여기서 직접
    // 쓰지 않는다. 문서 ID는 자동 생성(append-only 이력이라 존재 체크 대상이 아님).
    fun buildCreateWrites(
        userId: String,
        types: List<ConsentType>,
        version: String,
        agreedAt: LocalDateTime
    ): List<Pair<DocumentReference, Map<String, Any?>>> = types.map { type ->
        col.document() to mapOf(
            "user_id" to userId,
            "type" to type.name,
            "version" to version,
            "agreed_at" to agreedAt.toTimestamp(),
        )
    }

    private fun DocumentSnapshot.toConsentRecord() = ConsentRecord(
        recordId = id,
        userId = getString("user_id") ?: "",
        type = ConsentType.valueOf(getString("type") ?: ConsentType.TERMS_OF_SERVICE.name),
        version = getString("version") ?: "",
        agreedAt = getTimestamp("agreed_at")?.toLocalDateTime() ?: LocalDateTime.now(),
    )
}
