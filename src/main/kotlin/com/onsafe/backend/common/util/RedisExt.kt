package com.onsafe.backend.common.util

import com.onsafe.backend.common.exception.BusinessException
import com.onsafe.backend.common.exception.ErrorCode
import kotlinx.coroutines.CancellationException
import org.slf4j.Logger

// Redis SDK 예외가 컨트롤러까지 그대로 새지 않도록 BusinessException(REDIS_UNAVAILABLE)로 래핑한다.
// CancellationException은 반드시 그대로 다시 던져야 한다 — 여기서 잡아버리면 구조적 동시성의
// 취소 전파가 끊기고, 클라이언트가 정상적으로 연결을 끊었을 뿐인데 "Redis 오류"로 오인되는 로그가 남는다.
suspend fun <T> Logger.guardRedis(context: String, block: suspend () -> T): T = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    error("Redis 오류 ($context): ${e.message}", e)
    throw BusinessException(ErrorCode.REDIS_UNAVAILABLE)
}
