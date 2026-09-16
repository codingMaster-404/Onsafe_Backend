package com.onsafe.backend.domain.notification.repository

import com.google.cloud.firestore.Firestore
import com.google.cloud.firestore.Query
import com.onsafe.backend.common.util.await
import com.onsafe.backend.common.util.deleteInBatches
import com.onsafe.backend.common.util.toLocalDateTime
import com.onsafe.backend.common.util.toTimestamp
import com.onsafe.backend.domain.notification.model.entity.FcmToken
import org.springframework.stereotype.Repository
import java.time.LocalDateTime

// 한 계정이 들고 있을 수 있는 기기 수 상한. device_id는 앱이 보내는 ANDROID_ID라 서버가 검증할 수
// 없어, 값만 바꿔가며 등록을 반복하면 계정 하나에 문서가 무한히 쌓이고 알림 1건이 그만큼 증폭된다.
// 카메라 기기 + 본인 폰 + 태블릿 + 교체 직후 잔여를 덮는 값으로 5를 둔다.
private const val MAX_TOKENS_PER_USER = 5

/**
 * 기기별 FCM 토큰 — `users/{userId}/fcm_tokens/{deviceId}`.
 *
 * 이전에는 `users/{userId}.fcm_token` 필드 하나에 저장해 **마지막에 등록한 기기만** 알림을 받았다.
 * 같은 계정으로 카메라 기기와 본인 폰을 함께 쓰면 서로 덮어써 한쪽이 조용히 누락된다(B8).
 * 기기별 문서로 나누면 덮어쓰기가 사라지고, 만료된 토큰도 그 기기 문서만 지우면 된다.
 */
@Repository
class FcmTokenRepository(private val firestore: Firestore) {

    private fun col(userId: String) =
        firestore.collection("users").document(userId).collection("fcm_tokens")

    /** 같은 기기가 앱을 켤 때마다 호출되므로 upsert. 상한을 넘으면 가장 오래된 기기부터 정리한다. */
    suspend fun upsert(userId: String, deviceId: String, token: String) {
        col(userId).document(deviceId).set(
            mapOf(
                "fcm_token" to token,
                "device_id" to deviceId,
                "updated_at" to LocalDateTime.now().toTimestamp(),
            )
        ).await()
        evictOverflow(userId)
    }

    /** 발송 대상 조회. 상한을 넘는 문서가 어떤 이유로든 남아 있어도 발송이 폭주하지 않게 limit을 건다. */
    suspend fun findAll(userId: String): List<FcmToken> =
        col(userId)
            .orderBy("updated_at", Query.Direction.DESCENDING)
            .limit(MAX_TOKENS_PER_USER)
            .get().await().documents
            .map {
                FcmToken(
                    deviceId = it.getString("device_id") ?: it.id,
                    token = it.getString("fcm_token") ?: "",
                    updatedAt = it.getTimestamp("updated_at")?.toLocalDateTime() ?: LocalDateTime.now(),
                )
            }
            .filter { it.token.isNotBlank() }

    /**
     * 로그아웃·탈퇴 시 그 기기 토큰만 지운다. 저장된 값과 요청 토큰이 같을 때만 삭제하는 이유는,
     * 같은 deviceId로 다른 기기가 이미 갱신했을 때(기기 초기화 후 재설치 등) 남의 최신 토큰을
     * 지우지 않기 위해서다.
     */
    suspend fun delete(userId: String, deviceId: String, token: String) {
        val ref = col(userId).document(deviceId)
        val doc = ref.get().await()
        if (doc.exists() && doc.getString("fcm_token") == token) {
            ref.delete().await()
        }
    }

    /** FCM이 UNREGISTERED로 거절한 토큰 정리 — deviceId를 모를 수 있어 값으로 찾는다. */
    suspend fun deleteByToken(userId: String, token: String) {
        val docs = col(userId).whereEqualTo("fcm_token", token).get().await().documents
        firestore.deleteInBatches(docs.map { it.reference })
    }

    /** 탈퇴 cascade — 이 사용자의 기기 토큰을 전부 지운다. */
    suspend fun deleteAll(userId: String) {
        val docs = col(userId).get().await().documents
        firestore.deleteInBatches(docs.map { it.reference })
    }

    private suspend fun evictOverflow(userId: String) {
        val docs = col(userId).orderBy("updated_at", Query.Direction.DESCENDING).get().await().documents
        if (docs.size <= MAX_TOKENS_PER_USER) return
        firestore.deleteInBatches(docs.drop(MAX_TOKENS_PER_USER).map { it.reference })
    }
}
