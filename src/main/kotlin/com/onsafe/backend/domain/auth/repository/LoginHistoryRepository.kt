package com.onsafe.backend.domain.auth.repository

import com.google.cloud.firestore.DocumentSnapshot
import com.google.cloud.firestore.Firestore
import com.google.cloud.firestore.Query
import com.onsafe.backend.common.util.await
import com.onsafe.backend.common.util.deleteInBatches
import com.onsafe.backend.common.util.toLocalDateTime
import com.onsafe.backend.common.util.toTimestamp
import com.onsafe.backend.domain.auth.model.entity.LoginHistory
import com.onsafe.backend.domain.auth.model.entity.SecurityEventType
import org.springframework.stereotype.Repository
import java.time.LocalDateTime
import java.util.UUID

@Repository
class LoginHistoryRepository(private val firestore: Firestore) {

    private val col get() = firestore.collection("login_history")

    suspend fun save(history: LoginHistory): LoginHistory {
        val id = history.historyId.ifBlank { UUID.randomUUID().toString() }
        val saved = history.copy(historyId = id)
        col.document(id).set(saved.toMap()).await()
        return saved
    }

    suspend fun findRecentByUserId(userId: String, limit: Int = 50): List<LoginHistory> =
        col.whereEqualTo("user_id", userId)
            .orderBy("timestamp", Query.Direction.DESCENDING)
            .limit(limit)
            .get().await().documents.map { it.toLoginHistory() }

    // 문서를 하나씩 지우면 건수만큼 왕복이 생긴다 — 배치(최대 500건)로 묶는다(D3).
    // `deleteInBatches`는 guardian_links·fcm_tokens·consents가 이미 쓰는 공용 유틸이다.
    suspend fun deleteByUserId(userId: String): Long {
        val docs = col.whereEqualTo("user_id", userId).get().await().documents
        firestore.deleteInBatches(docs.map { it.reference })
        return docs.size.toLong()
    }

    // 정기 정리 잡용 — cutoff 이전 이력을 최대 [limit]개까지 삭제하고 삭제 건수 반환.
    // 한 회 실행에 처리량 상한을 두는 이유는 GCP 비용 예측 가능성 + Cloud Run 실행시간 제한 때문.
    // 잔여분은 다음 실행에서 이어서 처리된다 (90일 보관 정책상 일일 델타는 크지 않음).
    suspend fun deleteOlderThan(cutoff: LocalDateTime, limit: Int = 1000): Int {
        val docs = col.whereLessThan("timestamp", cutoff.toTimestamp())
            .limit(limit)
            .get().await().documents
        firestore.deleteInBatches(docs.map { it.reference })
        return docs.size
    }

    private fun DocumentSnapshot.toLoginHistory() = LoginHistory(
        historyId = id,
        userId = getString("user_id") ?: "",
        // event_type이 없는 기존 문서는 로그인 계열로 본다 — success 값으로 성공·실패를 가른다.
        eventType = getString("event_type")?.let { runCatching { SecurityEventType.valueOf(it) }.getOrNull() }
            ?: if (getBoolean("success") == true) SecurityEventType.LOGIN_SUCCESS else SecurityEventType.LOGIN_FAIL,
        ipAddress = getString("ip_address"),
        userAgent = getString("user_agent"),
        success = getBoolean("success") ?: false,
        failReason = getString("fail_reason"),
        targetUserId = getString("target_user_id"),
        timestamp = getTimestamp("timestamp")?.toLocalDateTime() ?: LocalDateTime.now()
    )

    private fun LoginHistory.toMap() = mapOf(
        "user_id" to userId,
        "event_type" to eventType.name,
        "ip_address" to ipAddress,
        "user_agent" to userAgent,
        "success" to success,
        "fail_reason" to failReason,
        "target_user_id" to targetUserId,
        "timestamp" to timestamp.toTimestamp()
    )
}