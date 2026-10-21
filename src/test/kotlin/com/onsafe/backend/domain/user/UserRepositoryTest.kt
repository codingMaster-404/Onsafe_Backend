package com.onsafe.backend.domain.user

import com.google.api.core.ApiFutures
import com.google.cloud.firestore.CollectionReference
import com.google.cloud.firestore.DocumentReference
import com.google.cloud.firestore.DocumentSnapshot
import com.google.cloud.firestore.Firestore
import com.google.cloud.firestore.Transaction
import com.onsafe.backend.domain.user.model.entity.User
import com.onsafe.backend.domain.user.repository.DeletionTaskRepository
import com.onsafe.backend.domain.user.repository.LookupSaveResult
import com.onsafe.backend.domain.user.repository.UserRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

// 개인정보 수정 트랜잭션이 409 사유(메일/전화)를 겹친 쪽 그대로 돌려주는지 확인한다(L2).
class UserRepositoryTest {

    private val firestore: Firestore = mockk()
    private val tx: Transaction = mockk(relaxed = true)
    private val repository = UserRepository(firestore, mockk<DeletionTaskRepository>())

    private val users: CollectionReference = mockk()
    private val emails: CollectionReference = mockk()
    private val phones: CollectionReference = mockk()
    private val newEmailRef: DocumentReference = mockk(relaxed = true)
    private val newPhoneRef: DocumentReference = mockk(relaxed = true)

    private val old = User(userId = "testUser", password = "p", name = "n", phone = "010-1111-2222", mail = "old@example.com")
    private val updated = old.copy(phone = "010-9999-8888", mail = "new@example.com")

    init {
        every { firestore.collection("users") } returns users
        every { firestore.collection("user_emails") } returns emails
        every { firestore.collection("user_phones") } returns phones
        every { users.document(any()) } returns mockk(relaxed = true)
        every { emails.document(any()) } returns mockk(relaxed = true)
        every { phones.document(any()) } returns mockk(relaxed = true)
        every { emails.document("new@example.com") } returns newEmailRef
        every { phones.document("01099998888") } returns newPhoneRef
        // set·delete는 Transaction을 돌려주는 제네릭이라 relaxed 기본값으로는 형 변환에 실패한다.
        every { tx.set(any<DocumentReference>(), any<Map<String, Any>>()) } returns tx
        every { tx.delete(any<DocumentReference>()) } returns tx
        // 트랜잭션 함수를 가짜 tx로 바로 실행한다.
        every { firestore.runTransaction(any<Transaction.Function<LookupSaveResult>>()) } answers {
            ApiFutures.immediateFuture(firstArg<Transaction.Function<LookupSaveResult>>().updateCallback(tx))
        }
    }

    private fun stubTaken(mailTaken: Boolean, phoneTaken: Boolean) {
        every { tx.get(newEmailRef) } returns ApiFutures.immediateFuture(snapshot(mailTaken))
        every { tx.get(newPhoneRef) } returns ApiFutures.immediateFuture(snapshot(phoneTaken))
    }

    private fun snapshot(exists: Boolean): DocumentSnapshot = mockk { every { exists() } returns exists }

    @Test
    fun `메일·전화를 함께 바꾸다 전화만 겹치면 PHONE_TAKEN`() = runTest {
        stubTaken(mailTaken = false, phoneTaken = true)

        assertEquals(LookupSaveResult.PHONE_TAKEN, repository.saveWithLookups(old, updated))
        verify(exactly = 0) { tx.set(any<DocumentReference>(), any<Map<String, Any>>()) }
    }

    @Test
    fun `메일만 겹치면 MAIL_TAKEN`() = runTest {
        stubTaken(mailTaken = true, phoneTaken = false)

        assertEquals(LookupSaveResult.MAIL_TAKEN, repository.saveWithLookups(old, updated))
    }

    @Test
    fun `둘 다 겹치면 메일을 먼저 알린다`() = runTest {
        stubTaken(mailTaken = true, phoneTaken = true)

        assertEquals(LookupSaveResult.MAIL_TAKEN, repository.saveWithLookups(old, updated))
    }

    @Test
    fun `겹치지 않으면 저장하고 SAVED`() = runTest {
        stubTaken(mailTaken = false, phoneTaken = false)

        assertEquals(LookupSaveResult.SAVED, repository.saveWithLookups(old, updated))
        verify { tx.set(newEmailRef, mapOf("user_id" to "testUser")) }
        verify { tx.set(newPhoneRef, mapOf("user_id" to "testUser")) }
    }
}
