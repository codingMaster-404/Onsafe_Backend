package com.onsafe.backend.domain.internal.controller

import com.onsafe.backend.common.exception.BusinessException
import com.onsafe.backend.common.exception.ErrorCode
import com.onsafe.backend.common.response.ApiResponse
import com.onsafe.backend.domain.camera.service.HeartbeatWatchdogJob
import io.swagger.v3.oas.annotations.Operation
import org.springframework.beans.factory.annotation.Value
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.security.MessageDigest

// Cloud Scheduler 전용 진입점. Cloud Run min-instances=0 이라 @Scheduled 가 트래픽 없는 시간대에
// 발동되지 않으므로, 스케줄 유발은 외부(Cloud Scheduler)가 담당하고 서버는 그 트리거로만 잡을
// 실행한다. Python AI 전용 InternalController 와 별도 파일로 분리해 AI 서버 발송 경로에는
// 시크릿 요구를 도입하지 않는다(별도 스코프).
//
// 인증: X-Internal-Auth 헤더에 담긴 시크릿을 constant-time 비교. 시크릿은 Secret Manager 슬롯
// INTERNAL_JOB_SECRET 을 배포 시 환경변수로 주입한다. 값이 비어 있으면 항상 FORBIDDEN 이라
// 로컬에서 실수로 노출돼도 endpoint 는 fail-closed 상태를 유지한다.
@RestController
@RequestMapping("/internal/jobs")
class InternalJobController(
    private val heartbeatWatchdogJob: HeartbeatWatchdogJob,
    @Value("\${internal.job.secret:}") private val expectedSecret: String
) {

    @Operation(summary = "카메라 heartbeat 워치독 실행 (Cloud Scheduler 트리거)", security = [])
    @PostMapping("/heartbeat-watchdog")
    suspend fun runHeartbeatWatchdog(
        @RequestHeader(value = "X-Internal-Auth", required = false) auth: String?
    ): ApiResponse<Unit> {
        requireInternalAuth(auth)
        heartbeatWatchdogJob.run()
        return ApiResponse.ok(message = "watchdog triggered")
    }

    private fun requireInternalAuth(header: String?) {
        if (expectedSecret.isBlank() || header.isNullOrBlank()) {
            throw BusinessException(ErrorCode.FORBIDDEN)
        }
        // 문자열 == 는 첫 불일치 지점에서 조기 반환해 타이밍 공격 여지가 있어 constant-time 비교.
        val a = expectedSecret.toByteArray(Charsets.UTF_8)
        val b = header.toByteArray(Charsets.UTF_8)
        if (!MessageDigest.isEqual(a, b)) throw BusinessException(ErrorCode.FORBIDDEN)
    }
}
