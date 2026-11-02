package com.onsafe.backend.common.storage

import com.google.auth.oauth2.AccessToken
import com.google.auth.oauth2.GoogleCredentials
import com.onsafe.backend.common.exception.BusinessException
import com.onsafe.backend.common.exception.ErrorCode
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.URLDecoder

class StorageServiceTest {

    private val service = StorageService(TestServiceAccount.credentials(), "test-bucket")

    @Test
    fun `주입받은 자격증명으로 V4 조회 URL을 서명한다`() = runTest {
        val url = URLDecoder.decode(service.generateSignedUrl("fall-videos/log1.mp4"), Charsets.UTF_8)

        assertTrue(url.contains("/test-bucket/fall-videos/log1.mp4"), url)
        assertTrue(url.contains("X-Goog-Algorithm=GOOG4-RSA-SHA256"), url)
        assertTrue(url.contains("X-Goog-Credential=${TestServiceAccount.CLIENT_EMAIL}/"), url)
        assertTrue(url.contains("X-Goog-Expires=3600"), url)
        assertTrue(url.contains("X-Goog-Signature="), url)
    }

    @Test
    fun `업로드 URL은 10분 유효하고 Content-Type을 서명 헤더에 포함한다`() = runTest {
        val url = URLDecoder.decode(service.generateSignedUploadUrl("fall-videos/log1.mp4"), Charsets.UTF_8)

        assertTrue(url.contains("/test-bucket/fall-videos/log1.mp4"), url)
        assertTrue(url.contains("X-Goog-Credential=${TestServiceAccount.CLIENT_EMAIL}/"), url)
        assertTrue(url.contains("X-Goog-Expires=600"), url)
        // 서명 헤더에 content-type이 들어가야 다른 형식으로 올린 PUT이 GCS에서 403이 된다
        assertTrue(url.contains("X-Goog-SignedHeaders=content-type;host"), url)
    }

    @Test
    fun `서명할 수 없는 자격증명이면 SDK 예외 대신 STORAGE_UNAVAILABLE`() = runTest {
        // 개인 키도 signBlob 권한도 없는 자격증명(예: 로컬 gcloud 사용자 ADC) — SDK가 IllegalStateException을 던진다
        val unsignable = StorageService(GoogleCredentials.create(AccessToken("fake-token", null)), "test-bucket")

        val e = assertThrows<BusinessException> { unsignable.generateSignedUrl("fall-videos/log1.mp4") }

        assertEquals(ErrorCode.STORAGE_UNAVAILABLE, e.errorCode)
        assertInstanceOf(IllegalStateException::class.java, e.cause)
    }
}
