package com.onsafe.backend.domain.auth.repository

import com.google.cloud.firestore.DocumentSnapshot
import com.google.cloud.firestore.Firestore
import com.google.cloud.firestore.Query
import com.onsafe.backend.common.util.await
import com.onsafe.backend.common.util.toLocalDateTime
import com.onsafe.backend.common.util.toTimestamp
import com.onsafe.backend.domain.auth.model.entity.LoginHistory
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

    suspend fun deleteByUserId(userId: String): Long {
        val docs = col.whereEqualTo("user_id", userId).get().await().documents
        docs.forEach { it.reference.delete().await() }
        return docs.size.toLong()
    }

    private fun DocumentSnapshot.toLoginHistory() = LoginHistory(
        historyId = id,
        userId = getString("user_id") ?: "",
        ipAddress = getString("ip_address") ?: "",
        userAgent = getString("user_agent") ?: "",
        success = getBoolean("success") ?: false,
        failReason = getString("fail_reason"),
        timestamp = getTimestamp("timestamp")?.toLocalDateTime() ?: LocalDateTime.now()
    )

    private fun LoginHistory.toMap() = mapOf(
        "user_id" to userId,
        "ip_address" to ipAddress,
        "user_agent" to userAgent,
        "success" to success,
        "fail_reason" to failReason,
        "timestamp" to timestamp.toTimestamp()
    )
}