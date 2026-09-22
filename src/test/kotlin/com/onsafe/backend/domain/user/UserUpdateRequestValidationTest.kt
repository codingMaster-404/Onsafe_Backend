package com.onsafe.backend.domain.user

import com.onsafe.backend.domain.user.model.dto.UserUpdateRequest
import jakarta.validation.Validation
import jakarta.validation.Validator
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 개인정보 수정 요청의 이름 검증. 앱은 빈 이름을 막지만(EditProfileActivity.validateAll),
 * 서버에 검증이 없으면 API 직접 호출로 ""가 저장돼 알림 문구가 "[] 낙상 감지"가 된다.
 */
class UserUpdateRequestValidationTest {

    private val validator: Validator = Validation.buildDefaultValidatorFactory().validator

    private fun nameViolations(name: String?) =
        validator.validate(UserUpdateRequest(name = name))
            .filter { it.propertyPath.toString() == "name" }

    @Test
    fun `name이 null이면 변경 안 함이므로 통과한다`() {
        assertTrue(nameViolations(null).isEmpty())
    }

    @Test
    fun `정상 이름은 통과한다`() {
        assertTrue(nameViolations("홍길동").isEmpty())
    }

    @Test
    fun `빈 문자열은 거부한다`() {
        assertFalse(nameViolations("").isEmpty())
    }

    @Test
    fun `공백만 있는 이름은 거부한다`() {
        assertFalse(nameViolations("   ").isEmpty())
    }

    @Test
    fun `30자를 넘는 이름은 거부한다`() {
        assertFalse(nameViolations("가".repeat(31)).isEmpty())
    }
}
