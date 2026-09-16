package com.onsafe.backend.domain.user.service

import com.onsafe.backend.domain.user.repository.DeletionTaskRepository
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

// 한 번에 처리할 작업 수. 파기는 계정당 Firestore·GCS 호출이 여러 건이라 너무 많이 잡으면
// Cloud Run 요청 타임아웃(300초)에 걸린다. 남은 작업은 다음 주기(1시간)에 이어서 처리한다.
private const val BATCH_SIZE = 50

/**
 * 탈퇴 파기 마무리 잡(C5). `deletion_tasks`에 남은 작업을 다시 실행한다.
 *
 * 탈퇴 요청은 "계정 삭제 + 작업 기록"까지만 보장하고 나머지 정리는 best-effort다 —
 * 클라이언트가 끊기거나(앱 타임아웃 10초·앱 종료) 인스턴스가 종료되면 중간에 멈춘다.
 * 이 잡이 **완료 소유자**라, 몇 번이 걸리든 파기를 끝까지 책임진다.
 *
 * 남아 있는 task 수 = 아직 파기가 끝나지 않은 계정 수라, 이 값이 그대로 파기 현황 지표가 된다.
 * Cloud Scheduler 가 `POST /internal/jobs/deletion-retry` 로 1시간마다 트리거한다.
 */
@Service
class DeletionRetryJob(
    private val deletionTaskRepository: DeletionTaskRepository,
    private val userService: UserService,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    suspend fun run() {
        val tasks = deletionTaskRepository.findPending(BATCH_SIZE)
        if (tasks.isEmpty()) return

        var done = 0
        for (task in tasks) {
            try {
                // purge는 멱등이라 이미 지워진 대상을 다시 지워도 문제가 없다.
                userService.purge(task)
                deletionTaskRepository.delete(task.userId)
                done++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 실패해도 task를 남겨 다음 주기에 다시 집어간다. attempts로 반복 실패를 관측한다.
                log.warn(
                    "탈퇴 파기 재시도 실패 — userId={}, attempts={}, cause={}",
                    task.userId, task.attempts + 1, e.javaClass.simpleName
                )
                runCatching {
                    deletionTaskRepository.recordFailure(task.userId, task.attempts + 1, e.javaClass.simpleName)
                }
            }
        }
        log.info("탈퇴 파기 재시도 완료: 대상 {}건 중 {}건 처리", tasks.size, done)
    }
}
