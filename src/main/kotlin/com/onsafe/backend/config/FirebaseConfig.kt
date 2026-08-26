package com.onsafe.backend.config

import com.google.auth.oauth2.GoogleCredentials
import com.google.cloud.firestore.Firestore
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.cloud.FirestoreClient
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.io.FileInputStream
import java.nio.file.Files
import java.nio.file.Paths

@Configuration
class FirebaseConfig {

    private val log = LoggerFactory.getLogger(FirebaseConfig::class.java)

    // 값이 비어있으면 ADC (Application Default Credentials) 사용
    // Cloud Run에서는 서비스 계정이 자동 주입되므로 미설정 상태 유지가 정상
    // 로컬 개발/도커 컴포즈에서는 serviceAccountKey.json 경로를 명시적으로 주입
    @Value("\${firebase.credentials:}")
    private lateinit var credentialsPath: String

    @Value("\${firebase.storage-bucket:}")
    private lateinit var storageBucket: String

    @Bean
    fun firestore(): Firestore {
        if (FirebaseApp.getApps().isEmpty()) {
            val credentials = loadCredentials()
            val builder = FirebaseOptions.builder().setCredentials(credentials)
            if (storageBucket.isNotBlank()) {
                builder.setStorageBucket(storageBucket)
            }
            FirebaseApp.initializeApp(builder.build())
        }
        return FirestoreClient.getFirestore()
    }

    private fun loadCredentials(): GoogleCredentials {
        // 1) 명시적 파일 경로가 설정되고 존재하면 파일 사용 (로컬/docker-compose)
        if (credentialsPath.isNotBlank() && Files.exists(Paths.get(credentialsPath))) {
            log.info("Firebase credentials: loading from file {}", credentialsPath)
            return GoogleCredentials.fromStream(FileInputStream(credentialsPath))
        }
        // 2) 그 외에는 ADC 사용 — Cloud Run / GCE / GKE에서 메타데이터 서버로부터 자동 획득
        //    GOOGLE_APPLICATION_CREDENTIALS 환경변수가 있으면 그 파일도 자동 인식
        log.info("Firebase credentials: using Application Default Credentials (ADC)")
        return GoogleCredentials.getApplicationDefault()
    }
}