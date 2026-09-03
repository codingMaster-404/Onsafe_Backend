package com.onsafe.backend.domain.auth

import com.onsafe.backend.domain.auth.model.dto.RegisterRequest
import com.onsafe.backend.domain.auth.model.dto.ResetPasswordRequest
import com.onsafe.backend.domain.user.model.dto.UserUpdateRequest
import jakarta.validation.Validation
import jakarta.validation.Validator
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 비밀번호 정규식(RegisterRequest / ResetPasswordRequest / UserUpdateRequest 동일 패턴) 회귀 테스트.
 *
 * 규칙: 영문 + 숫자 + 특수문자(@$!%*#?&) 모두 포함, 8~64자.
 * (?s) DOTALL 플래그 필수 — 없으면 개행 포함 정상 비밀번호가 거부됨.
 */
class PasswordPatternTest {

    private val validator: Validator = Validation.buildDefaultValidatorFactory().validator

    private fun hasPasswordViolation(password: String): Boolean =
        validator.validate(
            RegisterRequest(
                userId = "testUser",
                password = password,
                name = "홍길동",
                mail = "test@test.com",
                phone = "010-1234-5678"
            )
        ).any { it.propertyPath.toString() == "password" }

    @Test
    fun `영문+숫자+특수문자를 모두 포함한 8자 이상 비밀번호는 통과한다`() {
        assertFalse(hasPasswordViolation("abcd1234!"))
    }

    @Test
    fun `개행이 포함돼도 영문+숫자+특수문자를 만족하면 통과한다 (DOTALL 회귀)`() {
        assertFalse(hasPasswordViolation("abc123!\nXY"))
    }

    @Test
    fun `숫자가 없으면 거부된다`() {
        assertTrue(hasPasswordViolation("abcdefgh!"))
    }

    @Test
    fun `영문이 없으면 거부된다`() {
        assertTrue(hasPasswordViolation("12345678!"))
    }

    @Test
    fun `특수문자가 없으면 거부된다`() {
        assertTrue(hasPasswordViolation("abcd1234"))
    }

    @Test
    fun `허용되지 않는 특수문자만 있으면 거부된다`() {
        // ^ 은 허용 세트 [@$!%*#?&] 에 없음
        assertTrue(hasPasswordViolation("abcd1234^"))
    }

    @Test
    fun `하이픈·언더스코어·공백이 섞여도 필수 3종 충족하면 통과한다`() {
        assertFalse(hasPasswordViolation("ab_1 23!-x"))
    }

    @Test
    fun `ResetPasswordRequest도 개행 포함 비밀번호를 통과시킨다`() {
        val violations = validator.validate(
            ResetPasswordRequest(userId = "testUser", newPassword = "abc123!\nXY")
        )
        assertFalse(violations.any { it.propertyPath.toString() == "newPassword" })
    }

    @Test
    fun `ResetPasswordRequest는 특수문자 없으면 거부한다`() {
        val violations = validator.validate(
            ResetPasswordRequest(userId = "testUser", newPassword = "abcd1234")
        )
        assertTrue(violations.any { it.propertyPath.toString() == "newPassword" })
    }

    @Test
    fun `UserUpdateRequest도 개행 포함 비밀번호를 통과시킨다`() {
        val violations = validator.validate(UserUpdateRequest(password = "abc123!\nXY"))
        assertFalse(violations.any { it.propertyPath.toString() == "password" })
    }

    @Test
    fun `UserUpdateRequest는 특수문자 없으면 거부한다`() {
        val violations = validator.validate(UserUpdateRequest(password = "abcd1234"))
        assertTrue(violations.any { it.propertyPath.toString() == "password" })
    }
}