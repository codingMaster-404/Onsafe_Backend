package com.onsafe.backend.domain.user.repository

import com.google.cloud.firestore.DocumentReference
import com.google.cloud.firestore.DocumentSnapshot
import com.google.cloud.firestore.Firestore
import com.onsafe.backend.common.util.await
import com.onsafe.backend.common.util.createAllIfAllAbsent
import com.onsafe.backend.common.util.toLocalDateTime
import com.onsafe.backend.common.util.toTimestamp
import com.onsafe.backend.domain.user.model.entity.DeletionTask
import com.onsafe.backend.domain.user.model.entity.User
import org.springframework.stereotype.Repository
import java.time.LocalDateTime

@Repository
class UserRepository(
    private val firestore: Firestore,
    private val deletionTaskRepository: DeletionTaskRepository
) {

    private val col get() = firestore.collection("users")

    // 룩업 키는 정규화해서 쓴다(C9). 전화는 `@Pattern`이 하이픈을 선택으로 허용해
    // "010-1234-5678"과 "01012345678"이 다른 키가 되고, 메일은 대소문자만 달라도 다른 키가 돼
    // **같은 번호·같은 메일로 두 계정이 만들어진다**(중복 검사 우회).
    // 저장 값은 사용자가 입력한 표기 그대로 두고 키만 정규화한다 — 화면 표시가 달라지지 않게.
    private fun emailKey(mail: String) = mail.trim().lowercase()

    private fun phoneKey(phone: String) = phone.filter { it.isDigit() }

    private fun emailLookup(mail: String) = firestore.collection("user_emails").document(emailKey(mail))

    private fun phoneLookup(phone: String) = firestore.collection("user_phones").document(phoneKey(phone))

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

    // 정규화된 룩업 문서로 확인한다 — users 필드 쿼리는 대소문자·하이픈 차이를 못 걸러
    // 사전 확인은 "사용 가능"인데 가입 트랜잭션에서 409가 나는 어긋남이 생긴다(C9).
    suspend fun existsByMail(mail: String): Boolean =
        emailLookup(mail).get().await().exists()

    suspend fun existsByPhone(phone: String): Boolean =
        phoneLookup(phone).get().await().exists()

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
        val emailRef = emailLookup(user.mail)
        val phoneRef = phoneLookup(user.phone)
        val lookupData = mapOf("user_id" to user.userId)
        // 파기가 끝나지 않은 userId로는 가입할 수 없다(C5). 탈퇴는 계정 문서를 먼저 지우고 나머지
        // 정리를 잡에 넘기므로, 그 사이 같은 userId로 재가입하면 잡이 **새 계정의 데이터를 지운다**.
        // 잡이 파기를 끝내고 task를 삭제하면 그때부터 가입할 수 있다.
        val deletionTaskRef = deletionTaskRepository.documentRef(user.userId)
        return firestore.createAllIfAllAbsent(
            docsToCheck = listOf(userRef, emailRef, phoneRef, deletionTaskRef),
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

    /**
     * 탈퇴의 임계 구간(C5) — **파기 작업 기록과 계정 문서 삭제를 한 트랜잭션으로** 처리한다.
     *
     * 탈퇴는 소요 시간이 데이터 양에 비례해 가변인데 이를 동기 요청 안에서 끝내려 하면 클라이언트
     * 연결이 작업의 생명줄이 된다 — 앱의 읽기 타임아웃(OkHttp 기본 10초)·앱 종료·네트워크 끊김·
     * 인스턴스 종료가 모두 "절반만 지워지고 계정은 남은" 상태를 만든다.
     *
     * 이 두 쓰기만 원자적으로 끝내면 ① 사용자 관점에서는 탈퇴가 완료됐고(계정 소멸)
     * ② 남은 정리는 task를 보고 잡이 책임진다. 이후 어느 시점에 중단돼도 결과가 갈라지지 않는다.
     */
    suspend fun createDeletionTaskAndDeleteUser(task: DeletionTask) {
        val userRef = col.document(task.userId)
        val taskRef = deletionTaskRepository.documentRef(task.userId)
        firestore.runTransaction { tx ->
            tx.set(taskRef, deletionTaskRepository.toMap(task))
            tx.delete(userRef)
        }.await()
    }

    // 회원가입 시 함께 생성한 user_emails/{mail}, user_phones/{phone} 룩업 문서를 정리한다.
    // 탈퇴 cascade에서 users 본문 삭제와 별도로 호출해야 다음 가입 사이클에서 같은 mail/phone을
    // 다시 쓸 수 있다 — 룩업이 남아 있으면 트랜잭션의 유일성 검사에 걸려 재가입이 막힌다.
    suspend fun deleteEmailLookup(mail: String) {
        emailLookup(mail).delete().await()
    }

    suspend fun deletePhoneLookup(phone: String) {
        phoneLookup(phone).delete().await()
    }

    /**
     * 개인정보 수정 전용 — 사용자 문서 저장과 메일·전화 룩업 **이동**을 한 트랜잭션으로 처리한다(B1).
     *
     * 예전에는 `save()`만 해서 룩업이 가입 시점 값에 멈춰 있었다. 그래서 ① 다른 계정과 같은 메일·번호를
     * 가질 수 있었고 ② 바꾸기 전 메일 룩업이 영구히 남아 **아무도 그 메일로 재가입할 수 없었다**.
     *
     * @return false = 새 메일·전화를 다른 계정이 이미 쓰고 있음(호출부가 409로 변환)
     */
    suspend fun saveWithLookups(old: User, updated: User): Boolean {
        val mailChanged = emailKey(old.mail) != emailKey(updated.mail)
        val phoneChanged = phoneKey(old.phone) != phoneKey(updated.phone)
        if (!mailChanged && !phoneChanged) {
            save(updated)
            return true
        }

        val userRef = col.document(updated.userId)
        val newEmailRef = if (mailChanged) emailLookup(updated.mail) else null
        val newPhoneRef = if (phoneChanged) phoneLookup(updated.phone) else null
        val lookupData = mapOf("user_id" to updated.userId)

        return firestore.runTransaction { tx ->
            // Firestore 트랜잭션은 모든 read가 write보다 앞서야 한다 — future를 먼저 받아 병렬화한다.
            val emailFuture = newEmailRef?.let { tx.get(it) }
            val phoneFuture = newPhoneRef?.let { tx.get(it) }
            val taken = emailFuture?.get()?.exists() == true || phoneFuture?.get()?.exists() == true
            if (taken) return@runTransaction false

            tx.set(userRef, updated.toMap())
            newEmailRef?.let { tx.set(it, lookupData) }
            newPhoneRef?.let { tx.set(it, lookupData) }
            // 이전 룩업은 새 룩업을 만든 뒤 같은 트랜잭션에서 지운다 — 따로 지우면 그 사이에
            // 다른 사용자가 옛 메일로 가입해 이력이 뒤섞일 수 있다.
            if (mailChanged) tx.delete(emailLookup(old.mail))
            if (phoneChanged) tx.delete(phoneLookup(old.phone))
            true
        }.await()
    }

    private fun DocumentSnapshot.toUser() = User(
        userId = id,
        password = getString("password") ?: "",
        name = getString("name") ?: "",
        phone = getString("phone") ?: "",
        mail = getString("mail") ?: "",
        address = getString("address"),
        addressDetail = getString("address_detail"),
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
        "created_at" to createdAt.toTimestamp(),
        "marketing_consent" to marketingConsent,
        "marketing_consent_at" to marketingConsentAt?.toTimestamp(),
        "marketing_consent_withdrawn_at" to marketingConsentWithdrawnAt?.toTimestamp(),
    )
}
