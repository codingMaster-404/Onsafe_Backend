package com.onsafe.backend.common.storage

import com.google.auth.oauth2.ServiceAccountCredentials
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.util.Base64

/** 네트워크 없이 서명까지 확인하기 위한 테스트용 서비스 계정 — 실제 GCP 계정이 아니다. */
object TestServiceAccount {
    const val CLIENT_EMAIL = "test-signer@test-project.iam.gserviceaccount.com"

    private val privateKey: RSAPrivateKey by lazy {
        KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().private as RSAPrivateKey
    }

    fun credentials(): ServiceAccountCredentials =
        ServiceAccountCredentials.newBuilder()
            .setClientEmail(CLIENT_EMAIL)
            .setPrivateKey(privateKey)
            .setPrivateKeyId("test-key-id")
            .setProjectId("test-project")
            .build()

    /** serviceAccountKey.json과 같은 형식의 키 파일을 dir에 만든다. */
    fun writeKeyFile(dir: Path): Path {
        val pem = "-----BEGIN PRIVATE KEY-----\\n" +
            Base64.getEncoder().encodeToString(privateKey.encoded) +
            "\\n-----END PRIVATE KEY-----\\n"
        val json = """
            {
              "type": "service_account",
              "project_id": "test-project",
              "private_key_id": "test-key-id",
              "private_key": "$pem",
              "client_email": "$CLIENT_EMAIL",
              "client_id": "1234567890",
              "token_uri": "https://oauth2.googleapis.com/token"
            }
        """.trimIndent()
        return Files.writeString(dir.resolve("serviceAccountKey.json"), json)
    }
}
