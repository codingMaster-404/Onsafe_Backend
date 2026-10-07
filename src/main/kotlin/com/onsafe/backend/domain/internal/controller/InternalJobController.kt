package com.onsafe.backend.domain.internal.controller

import com.onsafe.backend.common.response.ApiResponse
import com.onsafe.backend.common.security.InternalAuthGuard
import com.onsafe.backend.domain.auth.service.LoginHistoryCleanupJob
import com.onsafe.backend.domain.camera.service.HeartbeatWatchdogJob
import com.onsafe.backend.domain.internal.service.RetentionBackfillJob
import com.onsafe.backend.domain.user.service.DeletionRetryJob
import io.swagger.v3.oas.annotations.Operation
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

// Cloud Scheduler 전용 진입점. Cloud Run min-instances=0 이라 @Scheduled 가 트래픽 없는 시간대
// (새벽 3시 KST 등)에 발동되지 않으므로, 스케줄 유발은 외부(Cloud Scheduler)가 담당하고
// 서버는 그 트리거로만 잡을 실행한다. Python AI 전용 InternalController 와 별도 파일로 분리해
// AI 서버 발송 경로에는 시크릿 요구를 도입하지 않는다(별도 스코프).
//
// 인증: InternalAuthGuard(X-Internal-Auth constant-time 비교)를 Python AI 서버용
// InternalController 와 공유한다 — 규칙을 각자 두면 한쪽만 검증이 빠진다.
@RestController
@RequestMapping("/internal/jobs")
class InternalJobController(
    private val loginHistoryCleanupJob: LoginHistoryCleanupJob,
    private val heartbeatWatchdogJob: HeartbeatWatchdogJob,
    private val deletionRetryJob: DeletionRetryJob,
    private val retentionBackfillJob: RetentionBackfillJob,
    private val internalAuthGuard: InternalAuthGuard
) {

    @Operation(summary = "로그인 이력 정리 (Cloud Scheduler 트리거)", security = [])
    @PostMapping("/login-history-cleanup")
    suspend fun runLoginHistoryCleanup(
        @RequestHeader(value = "X-Internal-Auth", required = false) auth: String?
    ): ApiResponse<Unit> {
        internalAuthGuard.require(auth)
        loginHistoryCleanupJob.run()
        return ApiResponse.ok(message = "cleanup triggered")
    }

    @Operation(summary = "카메라 heartbeat 워치독 실행 (Cloud Scheduler 트리거)", security = [])
    @PostMapping("/heartbeat-watchdog")
    suspend fun runHeartbeatWatchdog(
        @RequestHeader(value = "X-Internal-Auth", required = false) auth: String?
    ): ApiResponse<Unit> {
        internalAuthGuard.require(auth)
        heartbeatWatchdogJob.run()
        return ApiResponse.ok(message = "watchdog triggered")
    }

    @Operation(summary = "탈퇴 파기 재시도 (Cloud Scheduler 트리거)", security = [])
    @PostMapping("/deletion-retry")
    suspend fun runDeletionRetry(
        @RequestHeader(value = "X-Internal-Auth", required = false) auth: String?
    ): ApiResponse<Unit> {
        internalAuthGuard.require(auth)
        deletionRetryJob.run()
        return ApiResponse.ok(message = "deletion retry triggered")
    }

    @Operation(summary = "보관 기간 백필 — 기존 낙상 로그·알림에 expired_at 기록 (Cloud Scheduler 트리거)", security = [])
    @PostMapping("/retention-backfill")
    suspend fun runRetentionBackfill(
        @RequestHeader(value = "X-Internal-Auth", required = false) auth: String?
    ): ApiResponse<Unit> {
        internalAuthGuard.require(auth)
        retentionBackfillJob.run()
        return ApiResponse.ok(message = "retention backfill triggered")
    }
}
