package com.onsafe.backend.domain.user

import com.onsafe.backend.domain.user.model.entity.DeletionTask
import com.onsafe.backend.domain.user.repository.DeletionTaskRepository
import com.onsafe.backend.domain.user.service.DeletionRetryJob
import com.onsafe.backend.domain.user.service.UserService
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * 탈퇴 요청은 "계정 삭제 + 작업 기록"까지만 보장하고 나머지 정리는 best-effort다(C5).
 * 이 잡이 완료 소유자라, 중단된 파기를 끝까지 책임지는지 확인한다.
 */
class DeletionRetryJobTest {

    private val deletionTaskRepository: DeletionTaskRepository = mockk(relaxUnitFun = true)
    private val userService: UserService = mockk()
    private val job = DeletionRetryJob(deletionTaskRepository, userService)

    private fun task(userId: String, attempts: Int = 0) =
        DeletionTask(userId = userId, mail = "$userId@example.com", phone = "010-0000-0000", attempts = attempts)

    @Test
    fun `남은 작업이 없으면 아무것도 하지 않는다`() = runTest {
        coEvery { deletionTaskRepository.findPending(any()) } returns emptyList()

        job.run()

        coVerify(exactly = 0) { userService.purge(any()) }
    }

    @Test
    fun `파기에 성공하면 작업을 삭제한다`() = runTest {
        coEvery { deletionTaskRepository.findPending(any()) } returns listOf(task("userA"))
        coEvery { userService.purge(any()) } just Runs

        job.run()

        coVerify(exactly = 1) { deletionTaskRepository.delete("userA") }
    }

    @Test
    fun `파기에 실패하면 작업을 남기고 attempts를 올린다`() = runTest {
        coEvery { deletionTaskRepository.findPending(any()) } returns listOf(task("userA", attempts = 2))
        coEvery { userService.purge(any()) } throws RuntimeException("firestore down")

        job.run()

        // 다음 주기에 다시 집어가야 하므로 task를 지우면 안 된다.
        coVerify(exactly = 0) { deletionTaskRepository.delete(any()) }
        coVerify(exactly = 1) { deletionTaskRepository.recordFailure("userA", 3, "RuntimeException") }
    }

    @Test
    fun `한 건이 실패해도 나머지 작업은 계속 처리한다`() = runTest {
        coEvery { deletionTaskRepository.findPending(any()) } returns listOf(task("bad"), task("good"))
        coEvery { userService.purge(match { it.userId == "bad" }) } throws RuntimeException("boom")
        coEvery { userService.purge(match { it.userId == "good" }) } just Runs

        job.run()

        coVerify(exactly = 1) { deletionTaskRepository.delete("good") }
        coVerify(exactly = 0) { deletionTaskRepository.delete("bad") }
    }
}
