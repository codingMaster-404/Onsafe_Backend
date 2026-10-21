package com.onsafe.backend.domain.camera.repository

import com.google.cloud.firestore.DocumentSnapshot
import com.google.cloud.firestore.Firestore
import com.google.cloud.firestore.SetOptions
import com.onsafe.backend.common.util.await
import com.onsafe.backend.common.util.deleteInBatches
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

    // AI 점수 갱신 진입점(/internal/realtime). 스트리밍 중 초당 수 회 호출되므로 읽고-복사-덮어쓰기를 하면
    // 그 사이에 들어온 워치독 쓰기(감지 중단 상태 비움 등)를 이전 값으로 되돌린다 — 점수 3필드만 merge 한다.
    // 문서가 없으면 이 3필드로 새로 만든다(나머지 필드는 읽을 때 기본값).
    suspend fun updateScore(userId: String, score: Float, level: String) {
        col.document(userId).set(
            mapOf(
                "score" to score,
                "level" to level,
                "updated_at" to LocalDateTime.now().toTimestamp(),
            ),
            SetOptions.merge()
        ).await()
    }

    /**
      * 상태 문서(`realtime_data/{userId}`)와, 예전에 Python AI 서버가 같은 컬렉션에 자동 ID로 쌓아 둔
      * 분석용 features 문서를 함께 지운다(C11).
      *
      * 적재는 중단했지만(`_save_realtime_data` 주석 처리) **이미 쌓인 문서가 남아 있다.** 단건 delete만
      * 하던 이전 구현은 이 문서들을 놓쳐 탈퇴 후에도 관절 각도 시계열이 남았다 — 파기 의무 대상이다.
      */
    suspend fun deleteByUserId(userId: String) {
        col.document(userId).delete().await()
        val legacyDocs = col.whereEqualTo("user_id", userId).get().await().documents
        firestore.deleteInBatches(legacyDocs.map { it.reference })
    }

    // 앱 heartbeat 진입점. heartbeat 4필드만 merge 한다 — 문서 전체를 다시 쓰면 ① updated_at(AI 점수 갱신
    // 시각)이 heartbeat마다 바뀌어 AI가 멈춰도 최신처럼 보이고 ② 읽은 뒤 들어온 점수·워치독 쓰기를
    // 이전 값으로 되돌린다(updateScore와 같은 이유). 문서가 아직 없으면 이 4필드로 새로 만든다.
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
        col.document(userId).set(
            mapOf(
                "last_heartbeat_at" to timestamp.toTimestamp(),
                "is_online" to true,
                "power_save_mode" to powerSaveMode,
                "last_offline_notified_at" to null,
            ),
            SetOptions.merge()
        ).await()
        return existing
    }

    // 워치독 잡이 부르는 오프라인 후보 조회. is_online = true 인데 last_heartbeat_at 이 cutoff 이전인
    // 문서들. 등호+다른 필드 범위 조건이라 복합 인덱스 (is_online, last_heartbeat_at) 가 필요하다
    // — firestore.indexes.json 에 등록(없으면 운영에서 FAILED_PRECONDITION 으로 쿼리 실패).
    suspend fun findOfflineCandidates(cutoff: LocalDateTime, limit: Int = 500): List<RealtimeData> =
        col.whereEqualTo("is_online", true)
            .whereLessThan("last_heartbeat_at", cutoff.toTimestamp())
            .limit(limit)
            .get().await().documents.map { it.toRealtimeData() }

    // 워치독의 감지 중단 검사 대상 — heartbeat 가 cutoff 이후에 온(앱이 살아 있는) 문서들.
    // findOfflineCandidates 와 같은 (is_online, last_heartbeat_at) 복합 인덱스를 쓴다.
    suspend fun findOnlineCandidates(cutoff: LocalDateTime, limit: Int = 500): List<RealtimeData> =
        col.whereEqualTo("is_online", true)
            .whereGreaterThanOrEqualTo("last_heartbeat_at", cutoff.toTimestamp())
            .limit(limit)
            .get().await().documents.map { it.toRealtimeData() }

    // 오프라인 판정을 반영한다. 알림을 이미 보낸 시각을 기록해 재알림을 억제.
    // 감지 중단 상태도 함께 비운다 — 앱이 꺼진 동안은 오프라인 알림이 대신하고, 복구 후에는
    // 감지 중단을 처음부터 다시 판정해 "감지 재개" 알림이 "카메라 다시 온라인"과 겹치지 않게 한다.
    suspend fun markOffline(userId: String, notifiedAt: LocalDateTime) {
        col.document(userId).update(
            mapOf(
                "is_online" to false,
                "last_offline_notified_at" to notifiedAt.toTimestamp(),
                "stream_down_detected_at" to null,
                "last_stream_down_notified_at" to null,
            )
        ).await()
    }

    // 감지 중단 상태 갱신(B5). 두 필드만 부분 갱신한다 — score·heartbeat 필드는 다른 흐름이 쓴다.
    suspend fun updateStreamDown(userId: String, detectedAt: LocalDateTime?, notifiedAt: LocalDateTime?) {
        col.document(userId).update(
            mapOf(
                "stream_down_detected_at" to detectedAt?.toTimestamp(),
                "last_stream_down_notified_at" to notifiedAt?.toTimestamp(),
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
        streamDownDetectedAt = getTimestamp("stream_down_detected_at")?.toLocalDateTime(),
        lastStreamDownNotifiedAt = getTimestamp("last_stream_down_notified_at")?.toLocalDateTime(),
    )
}