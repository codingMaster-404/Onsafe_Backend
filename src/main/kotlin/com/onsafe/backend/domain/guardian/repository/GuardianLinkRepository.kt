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


    // AccessGuard 가 승인 시각(link.createdAt) 을 획득하기 위해 사용. 존재만 확인하는 exists 로는
    // 재페어링 이력 필터의 since 값을 얻을 수 없어 별도 조회가 필요.
    suspend fun find(guardianUserId: String, elderUserId: String): GuardianLink? {
        val doc = col.document(docId(guardianUserId, elderUserId)).get().await()
        return if (doc.exists()) doc.toLink() else null
    }

    // 유저별 활성 링크 존재 여부. 1:1 정책 강제(pair·issuePairingCode)와 이후 heartbeat 워치독의
    // "감시 대상 카메라 목록" 조회 등 두 축에서 공통으로 쓸 수 있어 별도 헬퍼로 노출한다.
    suspend fun existsByGuardian(guardianUserId: String): Boolean =
        col.whereEqualTo("guardian_user_id", guardianUserId).limit(1).get().await().isEmpty.not()

    suspend fun existsByElder(elderUserId: String): Boolean =
        col.whereEqualTo("elder_user_id", elderUserId).limit(1).get().await().isEmpty.not()

    // pair() 결과. Firestore에는 SQL UNIQUE 제약이 없어 두 축의 정책을 트랜잭션 read-set에 유저별
    // 쿼리를 얹어 강제한다:
    //  - 1:1 유일성: 각 유저는 자기 역할에서 최대 1개 관계만 가진다
    //  - 역할 배타성: 한 계정은 guardian 또는 elder 중 하나로만 등장한다 (겸용 불가)
    // 실패 원인을 서비스가 사용자에게 정확한 문구로 안내할 수 있도록 세분화해서 반환.
    enum class CreatePairingResult {
        CREATED,                    // 신규 관계 생성 성공
        SAME_PAIR_EXISTS,           // 정확히 같은 (guardian, elder) 쌍이 이미 존재
        GUARDIAN_ALREADY_LINKED,    // 이 guardian 이 이미 다른 elder 와 연결됨 (1:1 위반)
        ELDER_ALREADY_LINKED,       // 이 elder 가 이미 다른 guardian 과 연결됨 (1:1 위반)
        GUARDIAN_IS_ELDER_ELSEWHERE,// 이 guardian 이 다른 관계에서 elder 로 참여 중 (역할 배타성)
        ELDER_IS_GUARDIAN_ELSEWHERE,// 이 elder 가 다른 관계에서 guardian 으로 참여 중 (역할 배타성)
    }

    // 팀 결정: "교체 + 승인" 방식 채택 후 사용되지 않음(승인 흐름은 createOrReplace 사용).
    // 함수 자체는 시맨틱이 명확하고 다른 곳에서 재활용 가능성이 있어 남겨둔다.
    // 조회와 쓰기를 하나의 Firestore 트랜잭션으로 묶어 동시 pair() 요청 사이의 TOCTOU를 봉쇄한다.
    // Firestore 트랜잭션은 read set(문서·쿼리 결과)이 커밋 시점에 여전히 유효한지 낙관적 락으로
    // 확인하고, 아니면 자동 재시도한다. 재시도 시에는 read가 다시 실행되므로 트랜잭션 블록에
    // side-effect(로그·상태 변경)를 두면 안 됨 — 여기선 tx.get/tx.set만.
    // 모든 read 는 write 보다 먼저 와야 한다는 Firestore 제약을 지키기 위해 쿼리 5건을 앞에 모아둔다.
    suspend fun createIfAllUnique(link: GuardianLink): CreatePairingResult {
        val ref = col.document(docId(link.guardianUserId, link.elderUserId))
        // 1:1 유일성 검사용
        val guardianAsGuardianQuery = col.whereEqualTo("guardian_user_id", link.guardianUserId).limit(1)
        val elderAsElderQuery = col.whereEqualTo("elder_user_id", link.elderUserId).limit(1)
        // 역할 배타성 검사용 (반대 컬럼)
        val guardianAsElderQuery = col.whereEqualTo("elder_user_id", link.guardianUserId).limit(1)
        val elderAsGuardianQuery = col.whereEqualTo("guardian_user_id", link.elderUserId).limit(1)

        return firestore.runTransaction { tx ->
            // 앞에서 모든 read future를 발급받고 뒤에서 .get()으로 대기 — 라운드트립 병렬화.
            val exactFuture = tx.get(ref)
            val guardianAsGuardianFuture = tx.get(guardianAsGuardianQuery)
            val elderAsElderFuture = tx.get(elderAsElderQuery)
            val guardianAsElderFuture = tx.get(guardianAsElderQuery)
            val elderAsGuardianFuture = tx.get(elderAsGuardianQuery)

            when {
                exactFuture.get().exists() -> CreatePairingResult.SAME_PAIR_EXISTS
                !guardianAsGuardianFuture.get().isEmpty -> CreatePairingResult.GUARDIAN_ALREADY_LINKED
                !elderAsElderFuture.get().isEmpty -> CreatePairingResult.ELDER_ALREADY_LINKED
                !guardianAsElderFuture.get().isEmpty -> CreatePairingResult.GUARDIAN_IS_ELDER_ELSEWHERE
                !elderAsGuardianFuture.get().isEmpty -> CreatePairingResult.ELDER_IS_GUARDIAN_ELSEWHERE
                else -> {
                    tx.set(ref, link.toMap())
                    CreatePairingResult.CREATED
                }
            }
        }.await()
    }

    // "교체 + 승인" 방식 페어링 진입점. createIfAllUnique 와 달리 guardian·elder 각자의 기존
    // 관계는 삭제하고 새 관계로 교체한다. 역할 배타성(한 계정은 guardian 또는 elder 중 하나)만
    // 여전히 거부 — 이건 교체로 해결할 수 없는 축이라 여기선 유지.
    // 대체된 링크는 반환값에 실어 서비스 레이어가 이전 파트너에게 "해제됨" FCM 을 보낼 수 있게 한다.
    sealed class ReplaceResult {
        data class Created(val displaced: List<GuardianLink>) : ReplaceResult()
        object SamePairExists : ReplaceResult()
        object GuardianIsElderElsewhere : ReplaceResult()
        object ElderIsGuardianElsewhere : ReplaceResult()
    }

    suspend fun createOrReplace(link: GuardianLink): ReplaceResult {
        val ref = col.document(docId(link.guardianUserId, link.elderUserId))
        val guardianAsGuardianQuery = col.whereEqualTo("guardian_user_id", link.guardianUserId)
        val elderAsElderQuery = col.whereEqualTo("elder_user_id", link.elderUserId)
        val guardianAsElderQuery = col.whereEqualTo("elder_user_id", link.guardianUserId).limit(1)
        val elderAsGuardianQuery = col.whereEqualTo("guardian_user_id", link.elderUserId).limit(1)

        return firestore.runTransaction { tx ->
            // 모든 read 를 앞에 모아 라운드트립 병렬화 + Firestore 트랜잭션 제약(read → write) 준수.
            val exactFuture = tx.get(ref)
            val guardianExistingFuture = tx.get(guardianAsGuardianQuery)
            val elderExistingFuture = tx.get(elderAsElderQuery)
            val guardianRoleFuture = tx.get(guardianAsElderQuery)
            val elderRoleFuture = tx.get(elderAsGuardianQuery)

            when {
                exactFuture.get().exists() -> ReplaceResult.SamePairExists
                !guardianRoleFuture.get().isEmpty -> ReplaceResult.GuardianIsElderElsewhere
                !elderRoleFuture.get().isEmpty -> ReplaceResult.ElderIsGuardianElsewhere
                else -> {
                    // 교체: guardian 이 지금까지 감시하던 elder, elder 의 기존 guardian 을 모두 삭제.
                    // 1:1 정책상 각 축 최대 1건이지만 방어적으로 forEach.
                    val displaced = mutableListOf<GuardianLink>()
                    guardianExistingFuture.get().documents.forEach {
                        displaced.add(it.toLink())
                        tx.delete(it.reference)
                    }
                    elderExistingFuture.get().documents.forEach {
                        displaced.add(it.toLink())
                        tx.delete(it.reference)
                    }
                    tx.set(ref, link.toMap())
                    ReplaceResult.Created(displaced.toList())
                }
            }
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
