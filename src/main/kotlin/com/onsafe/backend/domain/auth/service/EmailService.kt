package com.onsafe.backend.domain.auth.service

import com.onsafe.backend.common.exception.BusinessException
import com.onsafe.backend.common.exception.ErrorCode
import kotlinx.coroutines.future.await
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import software.amazon.awssdk.core.exception.SdkClientException
import software.amazon.awssdk.services.sesv2.SesV2AsyncClient
import software.amazon.awssdk.services.sesv2.model.Body
import software.amazon.awssdk.services.sesv2.model.Content
import software.amazon.awssdk.services.sesv2.model.Destination
import software.amazon.awssdk.services.sesv2.model.EmailContent
import software.amazon.awssdk.services.sesv2.model.Message
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest
import software.amazon.awssdk.services.sesv2.model.SesV2Exception

@Service
class EmailService(
    private val sesClient: SesV2AsyncClient,
    @Value("\${aws.ses.from}") private val fromEmail: String
) {
    private val log = LoggerFactory.getLogger(javaClass)

    suspend fun sendResetCode(to: String, code: String) = sendEmail(
        to = to,
        subject = "[OnSafe] 비밀번호 재설정 인증코드",
        body = """
            안녕하세요, OnSafe입니다.

            비밀번호 재설정 인증코드: $code

            인증코드는 3분간 유효합니다.
            본인이 요청하지 않은 경우 이 메일을 무시해 주세요.
        """.trimIndent()
    )

    suspend fun sendEmailVerificationCode(to: String, code: String) = sendEmail(
        to = to,
        subject = "[OnSafe] 이메일 인증코드",
        body = """
            안녕하세요, OnSafe입니다.

            이메일 인증코드: $code

            인증코드는 3분간 유효합니다.
            본인이 요청하지 않은 경우 이 메일을 무시해 주세요.
        """.trimIndent()
    )

    private suspend fun sendEmail(to: String, subject: String, body: String) {
        try {
            sesClient.sendEmail(
                SendEmailRequest.builder()
                    .fromEmailAddress(fromEmail)
                    .destination(Destination.builder().toAddresses(to).build())
                    .content(
                        EmailContent.builder()
                            .simple(
                                Message.builder()
                                    .subject(Content.builder().data(subject).charset("UTF-8").build())
                                    .body(
                                        Body.builder()
                                            .text(Content.builder().data(body).charset("UTF-8").build())
                                            .build()
                                    )
                                    .build()
                            )
                            .build()
                    )
                    .build()
            ).await()
        } catch (e: SdkClientException) {
            log.error("SES 연결 실패 (수신: $to): ${e.message}", e)
            throw BusinessException(ErrorCode.MAIL_SEND_FAILED)
        } catch (e: SesV2Exception) {
            log.warn("SES 발송 거부 (수신: $to, 코드: ${e.statusCode()}): ${e.awsErrorDetails()?.errorMessage()}")
            throw BusinessException(ErrorCode.MAIL_SEND_FAILED)
        }
    }
}
