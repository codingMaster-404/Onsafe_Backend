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

    // 앱 heartbeat 진입점. score/level 같은 AI 파이프라인 값은 건드리지 않고 heartbeat 관련
    // 필드만 덮어쓴다 — Python AI 가 realtime_data 를 갱신하는 흐름과 독립적이라 update 로
    // 필드 부분 갱신. 문서가 아직 없으면 최초 heartbeat 시 신규 생성.
    // isOnline=true 로 표시하고 lastOfflineNotifiedAt 을 null 로 리셋한다 — heartbeat 가 왔다는
    // 것은 앱이 살아있다는 뜻이라 오프라인 재알림 게이트를 열어 다음 오프라인 감지 시 다시
    // 1회 알림이 나가게 한다. 복구 알림 발송 여부는 호출부(CameraService)가 결정.
    // 반환값: 이전 상태의 RealtimeData (null 이면 최초 heartbeat). 복구 감지에 활용.
    suspend fun upsertHeartbeat(
        userId: String,
        powerSaveMode: Boolean,
        timestamp: LocalDateTime = LocalDateTime.now(),
    ): RealtimeData? {
        val existing = findByUserId(userId)
        val next = existing?.copy(
            lastHeartbeatAt = timestamp,
            isOnline = true,
            powerSaveMode = powerSaveMode,
            lastOfflineNotifiedAt = null,
        ) ?: RealtimeData(
            userId = userId,
            lastHeartbeatAt = timestamp,
            isOnline = true,
            powerSaveMode = powerSaveMode,
            lastOfflineNotifiedAt = null,
        )
        save(next)
        return existing
    }

    // 워치독 잡이 부르는 오프라인 후보 조회. is_online = true 인데 last_heartbeat_at 이 cutoff 이전인
    // 문서들. Firestore 복합 인덱스가 필요할 수 있어 firestore.indexes.json 에 등록해야 한다.
    suspend fun findOfflineCandidates(cutoff: LocalDateTime, limit: Int = 500): List<RealtimeData> =
        col.whereEqualTo("is_online", true)
            .whereLessThan("last_heartbeat_at", cutoff.toTimestamp())
            .limit(limit)
            .get().await().documents.map { it.toRealtimeData() }

    // 오프라인 판정을 반영한다. 알림을 이미 보낸 시각을 기록해 재알림을 억제.
    suspend fun markOffline(userId: String, notifiedAt: LocalDateTime) {
        col.document(userId).update(
            mapOf(
                "is_online" to false,
                "last_offline_notified_at" to notifiedAt.toTimestamp(),
            )
        ).await()
    }

    private fun DocumentSnapshot.toRealtimeData() = RealtimeData(
        userId = id,
        score = getDouble("score")?.toFloat() ?: 0f,
        level = getString("level") ?: "정상",
        updatedAt = getTimestamp("updated_at")?.toLocalDateTime() ?: LocalDateTime.now(),
        lastHeartbeatAt = getTimestamp("last_heartbeat_at")?.toLocalDateTime(),
        isOnline = getBoolean("is_online") ?: false,
        powerSaveMode = getBoolean("power_save_mode") ?: false,
        lastOfflineNotifiedAt = getTimestamp("last_offline_notified_at")?.toLocalDateTime(),
    )

    private fun RealtimeData.toMap() = mapOf(
        "score" to score,
        "level" to level,
        "updated_at" to LocalDateTime.now().toTimestamp(),
        "last_heartbeat_at" to lastHeartbeatAt?.toTimestamp(),
        "is_online" to isOnline,
        "power_save_mode" to powerSaveMode,
        "last_offline_notified_at" to lastOfflineNotifiedAt?.toTimestamp(),
    )
}