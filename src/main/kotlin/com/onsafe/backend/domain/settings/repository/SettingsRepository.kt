package com.onsafe.backend.domain.settings.repository

import com.google.cloud.firestore.DocumentReference
import com.google.cloud.firestore.DocumentSnapshot
import com.google.cloud.firestore.Firestore
import com.onsafe.backend.common.util.await
import com.onsafe.backend.domain.settings.model.entity.UserSettings
import org.springframework.stereotype.Repository

@Repository
class SettingsRepository(private val firestore: Firestore) {

    private val col get() = firestore.collection("settings")

    suspend fun findByUserId(userId: String): UserSettings? {
        val doc = col.document(userId).get().await()
        return if (doc.exists()) doc.toSettings() else null
    }

    suspend fun save(settings: UserSettings): UserSettings {
        col.document(settings.userId).set(settings.toMap()).await()
        return settings
    }

    // UserRepository.createIfNotExists의 additionalWrites로 넘겨, 회원가입 시 users 문서와
    // 같은 트랜잭션에서 함께 생성되도록 (ref, data) 쌍만 만들어준다 — 여기서 직접 쓰지 않는다.
    fun buildCreateWrite(settings: UserSettings): Pair<DocumentReference, Map<String, Any?>> =
        col.document(settings.userId) to settings.toMap()

    suspend fun deleteByUserId(userId: String) {
        col.document(userId).delete().await()
    }

    private fun DocumentSnapshot.toSettings() = UserSettings(
        userId = id,
        notificationEnabled = getBoolean("notification_enabled") ?: true,
        soundEnabled = getBoolean("sound_enabled") ?: true,
        vibrationEnabled = getBoolean("vibration_enabled") ?: true,
    )

    private fun UserSettings.toMap() = mapOf(
        "notification_enabled" to notificationEnabled,
        "sound_enabled" to soundEnabled,
        "vibration_enabled" to vibrationEnabled,
    )
}
