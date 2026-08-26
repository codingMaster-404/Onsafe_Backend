package com.onsafe.backend.common.security

import org.springframework.stereotype.Component
import java.security.SecureRandom

/**
 * 이메일 인증, 비밀번호 재설정, 보호자 페어링 등 계정 탈취 경로가 될 수 있는 6자리 코드를
 * 공통으로 생성한다. 예측 가능한 kotlin.random.Random 대신 SecureRandom을 쓰며, 코드 형식/RNG를
 * 바꿔야 할 때 이 한 곳만 고치면 모든 사용처에 동일하게 적용된다.
 */
@Component
class VerificationCodeGenerator {

    private val secureRandom = SecureRandom()

    fun generate(): String = "%06d".format(secureRandom.nextInt(1_000_000))
}
