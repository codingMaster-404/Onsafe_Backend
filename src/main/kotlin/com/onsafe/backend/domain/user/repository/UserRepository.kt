package com.onsafe.backend.domain.user.repository

import com.google.cloud.firestore.DocumentReference
import com.google.cloud.firestore.DocumentSnapshot
import com.google.cloud.firestore.Firestore
import com.onsafe.backend.common.util.await
import com.onsafe.backend.common.util.createAllIfNotExists
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

    suspend fun existsByPhone(phone: String): Boolean =
        col.whereEqualTo("phone", phone).get().await().isEmpty.not()

    suspend fun save(user: User): User {
        col.document(user.userId).set(user.toMap()).await()
        return user
    }

    // 회원가입 전용 — AuthService.register()가 existsByUserId()로 미리 걸러내지만, 그 확인과
    // 이 저장 사이의 창에서 동시에 같은 userId로 가입 요청이 들어오면 사전 체크만으로는 막을 수
    // 없다(TOCTOU). GuardianLinkRepository.createIfNotExists와 동일하게 조회+쓰기를 트랜잭션으로
    // 묶어, 문서가 이미 존재하면 덮어쓰지 않고 false를 반환한다.
    // additionalWrites로 함께 생성돼야 하는 다른 컬렉션 문서(예: 가입 시 settings)를 같은
    // 트랜잭션에 실어, User는 생성됐는데 나머지가 안 만들어지는 부분 성공을 원천 차단한다.
    suspend fun createIfNotExists(
        user: User,
        additionalWrites: List<Pair<DocumentReference, Map<String, Any?>>> = emptyList()
    ): Boolean = firestore.createAllIfNotExists(
        col.document(user.userId),
        user.toMap(),
        *additionalWrites.toTypedArray()
    )

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
