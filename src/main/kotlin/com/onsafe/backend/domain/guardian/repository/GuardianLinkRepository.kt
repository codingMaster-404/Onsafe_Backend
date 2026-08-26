package com.onsafe.backend.domain.guardian.repository

import com.google.cloud.firestore.DocumentSnapshot
import com.google.cloud.firestore.Firestore
import com.onsafe.backend.common.util.await
import com.onsafe.backend.common.util.deleteInBatches
import com.onsafe.backend.common.util.toLocalDateTime
import com.onsafe.backend.common.util.toTimestamp
import com.onsafe.backend.domain.guardian.model.entity.GuardianLink
import org.springframework.stereotype.Repository
import java.time.LocalDateTime
import java.util.Base64

@Repository
class GuardianLinkRepository(private val firestore: Firestore) {

    private val col get() = firestore.collection("guardian_links")

    // 문서 ID를 guardianUserId_elderUserId 복합키로 고정해 동일 관계 중복 저장을 원천 차단한다.
    // userId 자체에 글자 수 제한이 없어 "_"를 그대로 구분자로 쓰면 예를 들어
    // (guardian="a_b", elder="c")와 (guardian="a", elder="b_c")가 똑같이 "a_b_c"로 충돌한다.
    // 각 파트를 URL-safe Base64(패딩 없음, 알파벳에 ":" 없음)로 인코딩한 뒤 ":"로 이어붙이면
    // 구분자가 인코딩 결과에 절대 나타나지 않아 충돌이 구조적으로 불가능하다.
    private val idEncoder = Base64.getUrlEncoder().withoutPadding()

    // toByteArray()에 charset을 명시하지 않으면 JVM 기본 charset(플랫폼/로케일에 따라 달라짐)을 쓴다.
    // 개발 환경(Windows)과 배포 컨테이너(Linux)가 비-ASCII userId에 대해 서로 다른 바이트를
    // 만들면 같은 (보호자,피보호자) 쌍인데도 환경마다 다른 문서ID가 생길 수 있어 반드시 고정한다.
    private fun docId(guardianUserId: String, elderUserId: String) =
        "${idEncoder.encodeToString(guardianUserId.toByteArray(Charsets.UTF_8))}:" +
            idEncoder.encodeToString(elderUserId.toByteArray(Charsets.UTF_8))

    suspend fun exists(guardianUserId: String, elderUserId: String): Boolean =
        col.document(docId(guardianUserId, elderUserId)).get().await().exists()

    // exists() 확인 후 별도로 save()하면 그 사이 창에서 같은 (보호자,피보호자) 쌍에 대한 동시
    // pair() 호출이 둘 다 "존재하지 않음"으로 통과해 하나가 다른 하나를 덮어쓸 수 있다(TOCTOU).
    // 조회와 쓰기를 트랜잭션으로 묶어 원자적으로 처리한다. 이미 존재했으면 false, 새로 만들었으면 true.
    suspend fun createIfNotExists(link: GuardianLink): Boolean {
        val ref = col.document(docId(link.guardianUserId, link.elderUserId))
        return firestore.runTransaction { tx ->
            val alreadyExists = tx.get(ref).get().exists()
            if (!alreadyExists) {
                tx.set(ref, link.toMap())
            }
            !alreadyExists
        }.await()
    }

    suspend fun findWardsOf(guardianUserId: String): List<GuardianLink> =
        col.whereEqualTo("guardian_user_id", guardianUserId).get().await().documents.map { it.toLink() }

    suspend fun findGuardiansOf(elderUserId: String): List<String> =
        col.whereEqualTo("elder_user_id", elderUserId).get().await().documents
            .mapNotNull { it.getString("guardian_user_id") }

    suspend fun delete(guardianUserId: String, elderUserId: String): Boolean {
        val ref = col.document(docId(guardianUserId, elderUserId))
        if (!ref.get().await().exists()) return false
        ref.delete().await()
        return true
    }

    // 계정 탈퇴 시 이 유저가 보호자·피보호자 어느 쪽으로 맺은 관계든 전부 정리한다.
    suspend fun deleteAllInvolving(userId: String) {
        val asGuardian = col.whereEqualTo("guardian_user_id", userId).get().await().documents
        val asElder = col.whereEqualTo("elder_user_id", userId).get().await().documents
        firestore.deleteInBatches((asGuardian + asElder).map { it.reference })
    }

    private fun DocumentSnapshot.toLink() = GuardianLink(
        guardianUserId = getString("guardian_user_id") ?: "",
        elderUserId = getString("elder_user_id") ?: "",
        createdAt = getTimestamp("created_at")?.toLocalDateTime() ?: LocalDateTime.now(),
    )

    private fun GuardianLink.toMap() = mapOf(
        "guardian_user_id" to guardianUserId,
        "elder_user_id" to elderUserId,
        "created_at" to createdAt.toTimestamp(),
    )
}
