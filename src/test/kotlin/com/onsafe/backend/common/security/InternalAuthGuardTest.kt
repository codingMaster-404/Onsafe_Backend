package com.onsafe.backend.common.security

import com.onsafe.backend.common.exception.BusinessException
import com.onsafe.backend.common.exception.ErrorCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * /internal 하위 경로는 JWT 필터를 타지 않고 Kotlin 서비스가 공개 노출돼 있어,
 * 이 헤더 검증이 유일한 관문이다(A5).
 */
class InternalAuthGuardTest {

    private val guard = InternalAuthGuard(expectedSecret = "s3cr3t-internal-value")

    private fun errorOf(header: String?): ErrorCode =
        assertThrows<BusinessException> { guard.require(header) }.errorCode

    @Test
    fun `시크릿이 일치하면 통과한다`() {
        guard.require("s3cr3t-internal-value")
    }

    @Test
    fun `헤더가 없으면 FORBIDDEN`() {
        assertEquals(ErrorCode.FORBIDDEN, errorOf(null))
        assertEquals(ErrorCode.FORBIDDEN, errorOf("  "))
    }

    @Test
    fun `시크릿이 다르면 FORBIDDEN`() {
        assertEquals(ErrorCode.FORBIDDEN, errorOf("wrong-secret"))
        // 앞부분만 같은 값도 거부 — constant-time 비교라 조기 반환하지 않는다.
        assertEquals(ErrorCode.FORBIDDEN, errorOf("s3cr3t"))
    }

    @Test
    fun `서버에 시크릿이 설정되지 않았으면 무엇을 보내도 거부한다 (fail-closed)`() {
        val unconfigured = InternalAuthGuard(expectedSecret = "")

        assertEquals(ErrorCode.FORBIDDEN, assertThrows<BusinessException> { unconfigured.require("anything") }.errorCode)
        assertEquals(ErrorCode.FORBIDDEN, assertThrows<BusinessException> { unconfigured.require("") }.errorCode)
    }
}
