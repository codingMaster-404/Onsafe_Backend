package com.onsafe.backend.domain.user.repository

import com.google.cloud.firestore.DocumentSnapshot
import com.google.cloud.firestore.Firestore
import com.onsafe.backend.common.util.await
import com.onsafe.backend.common.util.toLocalDateTime
import com.onsafe.backend.common.util.toTimestamp
import com.onsafe.backend.domain.user.model.entity.DeletionTask
import org.springframework.stereotype.Repository
import java.time.LocalDateTime

/**
 * 탈퇴 파기 작업 큐(C5). Cloud Tasks·Pub/Sub 대신 Firestore 컬렉션을 큐로 쓰고 Cloud Scheduler가
 * 주기적으로 처리한다 — 이미 있는 `/internal/jobs` + Scheduler 구조를 그대로 쓰므로 추가 인프라가 없다.
 * 탈퇴량이 늘면 Cloud Tasks로 옮길 수 있다(재시도·백오프 내장).
 *
 * 남아 있는 문서 수 = **아직 파기가 끝나지 않은 계정 수**라 파기 현황이 그대로 지표가 된다.
 */
@Repository
class DeletionTaskRepository(private val firestore: Firestore) {

    private val col get() = firestore.collection("deletion_tasks")

    fun documentRef(userId: String) = col.document(userId)

    suspend fun find(userId: String): DeletionTask? {
        val doc = col.document(userId).get().await()
        return if (doc.exists()) doc.toTask() else null
    }

    // 오래된 것부터 처리한다 — 파기가 밀린 계정이 계속 뒤로 밀리지 않게.
    suspend fun findPending(limit: Int): List<DeletionTask> =
        col.orderBy("created_at").limit(limit).get().await().documents.map { it.toTask() }

    suspend fun delete(userId: String) {
        col.document(userId).delete().await()
    }

    /** 재시도 실패 기록 — 다음 잡 실행에서 다시 집어간다. */
    suspend fun recordFailure(userId: String, attempts: Int, error: String) {
        col.document(userId).update(
            mapOf(
                "attempts" to attempts,
                "last_error" to error.take(300),
            )
        ).await()
    }

    fun toMap(task: DeletionTask): Map<String, Any?> = mapOf(
        "user_id" to task.userId,
        "mail" to task.mail,
        "phone" to task.phone,
        "log_ids" to task.logIds,
        "attempts" to task.attempts,
        "last_error" to task.lastError,
        "created_at" to task.createdAt.toTimestamp(),
    )

    @Suppress("UNCHECKED_CAST")
    private fun DocumentSnapshot.toTask() = DeletionTask(
        userId = id,
        mail = getString("mail") ?: "",
        phone = getString("phone") ?: "",
        logIds = (get("log_ids") as? List<String>) ?: emptyList(),
        attempts = (getLong("attempts") ?: 0L).toInt(),
        lastError = getString("last_error"),
        createdAt = getTimestamp("created_at")?.toLocalDateTime() ?: LocalDateTime.now(),
    )
}
