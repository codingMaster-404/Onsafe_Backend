package com.onsafe.backend.domain.camera.repository

import com.google.cloud.firestore.DocumentSnapshot
import com.google.cloud.firestore.Firestore
import com.onsafe.backend.common.util.await
import com.onsafe.backend.common.util.toLocalDateTime
import com.onsafe.backend.common.util.toTimestamp
import com.onsafe.backend.domain.camera.model.entity.RealtimeData
import org.springframework.stereotype.Repository
import java.time.LocalDateTime

@Repository
class RealtimeDataRepository(private val firestore: Firestore) {

    private val col get() = firestore.collection("realtime_data")

    suspend fun findByUserId(userId: String): RealtimeData? {
        val doc = col.document(userId).get().await()
        return if (doc.exists()) doc.toRealtimeData() else null
    }

    suspend fun save(data: RealtimeData): RealtimeData {
        col.document(data.userId).set(data.toMap()).await()
        return data
    }

    // 문서 ID가 곧 userId라 단건 delete로 충분(추가 쿼리 불필요).
    suspend fun deleteByUserId(userId: String) {
        col.document(userId).delete().await()
    }

    private fun DocumentSnapshot.toRealtimeData() = RealtimeData(
        userId = id,
        score = getDouble("score")?.toFloat() ?: 0f,
        level = getString("level") ?: "정상",
        updatedAt = getTimestamp("updated_at")?.toLocalDateTime() ?: LocalDateTime.now()
    )

    private fun RealtimeData.toMap() = mapOf(
        "score" to score,
        "level" to level,
        "updated_at" to LocalDateTime.now().toTimestamp()
    )
}
