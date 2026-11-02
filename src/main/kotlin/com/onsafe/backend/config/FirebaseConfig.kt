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

    // 값이 비어있으면 ADC (Application Default Credentials) 사용
    // Cloud Run에서는 서비스 계정이 자동 주입되므로 미설정 상태 유지가 정상
    // 로컬 개발/도커 컴포즈에서는 serviceAccountKey.json 경로를 명시적으로 주입
    @Value("\${firebase.credentials:}")
    private lateinit var credentialsPath: String

    @Value("\${firebase.storage.bucket:}")
    private lateinit var storageBucket: String

    // Firestore·FCM(FirebaseApp)과 GCS(StorageService)가 같은 자격증명을 쓰도록 빈으로 공유한다.
    @Bean
    fun googleCredentials(): GoogleCredentials = loadCredentials(credentialsPath)

    @Bean
    fun firestore(googleCredentials: GoogleCredentials): Firestore {
        if (FirebaseApp.getApps().isEmpty()) {
            val builder = FirebaseOptions.builder().setCredentials(googleCredentials)
            if (storageBucket.isNotBlank()) {
                builder.setStorageBucket(storageBucket)
            }
            // ADC 모드에서는 project ID 가 자동 추출 안 될 수 있어 환경변수로 명시한다
            // (Cloud Run 은 GOOGLE_CLOUD_PROJECT 를 자동 주입하지 않으므로 배포 워크플로에서 전달)
            val projectId = System.getenv("GOOGLE_CLOUD_PROJECT")
            if (!projectId.isNullOrBlank()) {
                builder.setProjectId(projectId)
                log.info("Firebase project ID: {}", projectId)
            }
            FirebaseApp.initializeApp(builder.build())
        }
        return FirestoreClient.getFirestore()
    }

    companion object {
        private val log = LoggerFactory.getLogger(FirebaseConfig::class.java)

        internal fun loadCredentials(
            credentialsPath: String,
            applicationDefault: () -> GoogleCredentials = GoogleCredentials::getApplicationDefault
        ): GoogleCredentials {
            // 1) 명시적 파일 경로가 설정되고 존재하면 파일 사용 (로컬/docker-compose)
            if (credentialsPath.isNotBlank() && Files.exists(Paths.get(credentialsPath))) {
                log.info("Firebase credentials: loading from file {}", credentialsPath)
                return FileInputStream(credentialsPath).use { GoogleCredentials.fromStream(it) }
            }
            // 2) 그 외에는 ADC 사용 — Cloud Run / GCE / GKE에서 메타데이터 서버로부터 자동 획득
            //    GOOGLE_APPLICATION_CREDENTIALS 환경변수가 있으면 그 파일도 자동 인식
            //    Cloud Run의 ADC는 개인 키가 없어 GCS V4 서명을 IAM signBlob으로 한다
            //    → 런타임 SA에 자기 자신에 대한 roles/iam.serviceAccountTokenCreator 필요
            log.info("Firebase credentials: using Application Default Credentials (ADC)")
            return applicationDefault()
        }
    }
}