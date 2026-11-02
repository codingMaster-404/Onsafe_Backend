package com.onsafe.backend.config

import com.google.auth.oauth2.GoogleCredentials
import com.google.auth.oauth2.ServiceAccountCredentials
import com.onsafe.backend.common.storage.TestServiceAccount
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class FirebaseConfigTest {

    private val adc: GoogleCredentials = mockk()

    @Test
    fun `키 파일이 있으면 파일의 서비스 계정을 쓰고 ADC는 부르지 않는다`(@TempDir dir: Path) {
        val keyFile = TestServiceAccount.writeKeyFile(dir)

        val credentials = FirebaseConfig.loadCredentials(keyFile.toString()) { error("ADC를 부르면 안 된다") }

        assertEquals(TestServiceAccount.CLIENT_EMAIL, (credentials as ServiceAccountCredentials).clientEmail)
    }

    @Test
    fun `경로가 비어 있으면 ADC를 쓴다`() {
        assertSame(adc, FirebaseConfig.loadCredentials("") { adc })
    }

    @Test
    fun `경로가 있어도 파일이 없으면 ADC를 쓴다 — Cloud Run 기본값 serviceAccountKey_json`(@TempDir dir: Path) {
        val missing = dir.resolve("serviceAccountKey.json").toString()

        assertSame(adc, FirebaseConfig.loadCredentials(missing) { adc })
    }
}
