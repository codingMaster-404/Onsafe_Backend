package com.onsafe.backend.domain.user.repository

import com.google.cloud.firestore.DocumentReference
import com.google.cloud.firestore.DocumentSnapshot
import com.google.cloud.firestore.Firestore
import com.onsafe.backend.common.util.await
import com.onsafe.backend.common.util.createAllIfAllAbsent
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

    // 회원가입 전용 — userId·mail·phone 세 축 모두에서 유일해야 하는데 Firestore에는 SQL UNIQUE
    // 제약이 없어 각 축마다 룩업 문서(user_emails/{mail}, user_phones/{phone})를 별도로 두고
    // users 문서와 함께 한 트랜잭션에 묶는다. 사전 existsByMail/existsByPhone 체크와 실제 저장
    // 사이 창에서 동시 요청이 통과해 mail/phone 중복이 저장되는 TOCTOU를 원천 차단한다.
    // additionalWrites로 함께 생성돼야 하는 다른 컬렉션 문서(가입 시 settings, consents)를 같은
    // 트랜잭션에 실어, User는 생성됐는데 나머지가 안 만들어지는 부분 성공도 방지한다.
    suspend fun createIfNotExists(
        user: User,
        additionalWrites: List<Pair<DocumentReference, Map<String, Any?>>> = emptyList()
    ): Boolean {
        val userRef = col.document(user.userId)
        val emailRef = firestore.collection("user_emails").document(user.mail)
        val phoneRef = firestore.collection("user_phones").document(user.phone)
        val lookupData = mapOf("user_id" to user.userId)
        return firestore.createAllIfAllAbsent(
            docsToCheck = listOf(userRef, emailRef, phoneRef),
            docsToWrite = listOf(
                userRef to user.toMap(),
                emailRef to lookupData,
                phoneRef to lookupData,
            ) + additionalWrites
        )
    }

    suspend fun deleteByUserId(userId: String) {
        col.document(userId).delete().await()
    }

    // 회원가입 시 함께 생성한 user_emails/{mail}, user_phones/{phone} 룩업 문서를 정리한다.
    // 탈퇴 cascade에서 users 본문 삭제와 별도로 호출해야 다음 가입 사이클에서 같은 mail/phone을
    // 다시 쓸 수 있다 — 룩업이 남아 있으면 트랜잭션의 유일성 검사에 걸려 재가입이 막힌다.
    suspend fun deleteEmailLookup(mail: String) {
        firestore.collection("user_emails").document(mail).delete().await()
    }

    suspend fun deletePhoneLookup(phone: String) {
        firestore.collection("user_phones").document(phone).delete().await()
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
