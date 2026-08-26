package com.onsafe.backend.domain.notification.repository

import com.google.cloud.firestore.DocumentSnapshot
import com.google.cloud.firestore.Firestore
import com.google.cloud.firestore.Query
import com.onsafe.backend.common.util.await
import com.onsafe.backend.common.util.deleteInBatches
import com.onsafe.backend.common.util.toLocalDateTime
import com.onsafe.backend.common.util.toTimestamp
import com.onsafe.backend.domain.notification.model.entity.Notification
import org.springframework.stereotype.Repository
import java.time.LocalDateTime

@Repository
class NotificationRepository(private val firestore: Firestore) {

    private val col get() = firestore.collection("notifications")

    suspend fun save(notification: Notification): Notification {
        val ref = col.document()
        val saved = notification.copy(notificationId = ref.id)
        ref.set(saved.toMap()).await()
        return saved
    }

    // Firestore 복합 인덱스(user_id ASC, created_at DESC) 필요 — firestore.indexes.json 참고.
    suspend fun findRecentByUserId(userId: String, limit: Int = 50): List<Notification> =
        col.whereEqualTo("user_id", userId)
            .orderBy("created_at", Query.Direction.DESCENDING)
            .limit(limit)
            .get().await().documents.map { it.toNotification() }

    suspend fun deleteByUserId(userId: String) {
        val docs = col.whereEqualTo("user_id", userId).get().await().documents
        firestore.deleteInBatches(docs.map { it.reference })
    }

    // 회원탈퇴 시 본인 알림뿐 아니라, 그 사용자의 낙상 이벤트를 참조해 보호자 인박스에
    // 별도로 저장된 알림 사본도 함께 지운다(notifyElderAndGuardians가 피보호자 본인 + 보호자
    // 각각에게 개별 문서를 남기므로, 탈퇴 후에도 보호자 쪽에 남은 사본이 존재 이름·삭제된
    // logId를 계속 가리키는 stale 데이터로 남는 것을 막기 위함).
    suspend fun deleteByLogIds(logIds: List<String>) {
        if (logIds.isEmpty()) return
        // Firestore whereIn은 값 30개까지만 허용해 청크로 나눈다.
        val refs = logIds.chunked(30).flatMap { chunk ->
            col.whereIn("log_id", chunk).get().await().documents.map { it.reference }
        }
        firestore.deleteInBatches(refs)
    }

    suspend fun markRead(notificationId: String, userId: String): Notification? {
        val doc = col.document(notificationId).get().await()
        if (!doc.exists() || doc.getString("user_id") != userId) return null
        doc.reference.update("is_read", true).await()
        return doc.toNotification().copy(isRead = true)
    }

    private fun DocumentSnapshot.toNotification() = Notification(
        notificationId = id,
        userId = getString("user_id") ?: "",
        title = getString("title") ?: "",
        body = getString("body") ?: "",
        logId = getString("log_id"),
        score = getDouble("score")?.toFloat(),
        fall = getBoolean("fall") ?: false,
        isRead = getBoolean("is_read") ?: false,
        createdAt = getTimestamp("created_at")?.toLocalDateTime() ?: LocalDateTime.now(),
    )

    private fun Notification.toMap() = mapOf(
        "user_id" to userId,
        "title" to title,
        "body" to body,
        "log_id" to logId,
        "score" to score,
        "fall" to fall,
        "is_read" to isRead,
        "created_at" to createdAt.toTimestamp(),
    )
}
