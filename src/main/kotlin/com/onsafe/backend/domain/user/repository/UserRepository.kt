package com.onsafe.backend.domain.user.repository

import com.google.cloud.firestore.DocumentSnapshot
import com.google.cloud.firestore.Firestore
import com.onsafe.backend.common.util.await
import com.onsafe.backend.common.util.toLocalDateTime
import com.onsafe.backend.common.util.toTimestamp
import com.onsafe.backend.domain.user.model.entity.User
import org.springframework.stereotype.Repository
import java.time.LocalDateTime

@Repository
class UserRepository(private val firestore: Firestore) {

    private val col get() = firestore.collection("users")

    suspend fun findByUserId(userId: String): User? {
        val doc = col.document(userId).get().await()
        return if (doc.exists()) doc.toUser() else null
    }

    suspend fun findByMail(mail: String): User? {
        val snap = col.whereEqualTo("mail", mail).get().await()
        return snap.documents.firstOrNull()?.toUser()
    }

    suspend fun existsByUserId(userId: String): Boolean =
        col.document(userId).get().await().exists()

    suspend fun existsByMail(mail: String): Boolean =
        col.whereEqualTo("mail", mail).get().await().isEmpty.not()

    suspend fun save(user: User): User {
        col.document(user.userId).set(user.toMap()).await()
        return user
    }

    suspend fun deleteByUserId(userId: String) {
        col.document(userId).delete().await()
    }

    suspend fun clearFcmToken(userId: String) {
        col.document(userId).update("fcm_token", null).await()
    }

    private fun DocumentSnapshot.toUser() = User(
        userId = id,
        password = getString("password") ?: "",
        name = getString("name") ?: "",
        phone = getString("phone") ?: "",
        mail = getString("mail") ?: "",
        address = getString("address"),
        addressDetail = getString("address_detail"),
        fcmToken = getString("fcm_token"),
        createdAt = getTimestamp("created_at")?.toLocalDateTime() ?: LocalDateTime.now(),
        marketingConsent = getBoolean("marketing_consent") ?: false,
        marketingConsentAt = getTimestamp("marketing_consent_at")?.toLocalDateTime(),
        marketingConsentWithdrawnAt = getTimestamp("marketing_consent_withdrawn_at")?.toLocalDateTime(),
    )

    private fun User.toMap() = mapOf(
        "password" to password,
        "name" to name,
        "phone" to phone,
        "mail" to mail,
        "address" to address,
        "address_detail" to addressDetail,
        "fcm_token" to fcmToken,
        "created_at" to createdAt.toTimestamp(),
        "marketing_consent" to marketingConsent,
        "marketing_consent_at" to marketingConsentAt?.toTimestamp(),
        "marketing_consent_withdrawn_at" to marketingConsentWithdrawnAt?.toTimestamp(),
    )
}
