package com.onsafe.backend.common.storage

import com.google.auth.oauth2.GoogleCredentials
import com.google.cloud.storage.BlobId
import com.google.cloud.storage.BlobInfo
import com.google.cloud.storage.HttpMethod
import com.google.cloud.storage.Storage
import com.google.cloud.storage.StorageOptions
import com.onsafe.backend.common.exception.BusinessException
import com.onsafe.backend.common.exception.ErrorCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.util.concurrent.TimeUnit

@Service
class StorageService(
    // FirebaseConfig.googleCredentials — 키 파일이 있으면 파일, 없으면 ADC(Cloud Run)
    private val credentials: GoogleCredentials,
    @Value("\${firebase.storage.bucket:}") private val bucketName: String
) {
    private val log = LoggerFactory.getLogger(StorageService::class.java)

    private val gcsStorage: Storage by lazy {
        StorageOptions.newBuilder()
            .setCredentials(credentials)
            .build()
            .service
    }

    // GCS SDK 예외가 컨트롤러까지 새지 않도록 STORAGE_UNAVAILABLE로 래핑한다(guardRedis와 같은 방식).
    // 원인은 래핑 전에 여기서 남긴다 — 호출부 로그엔 BusinessException만 보인다.
    private suspend fun <T> guardGcs(context: String, gcsPath: String, block: () -> T): T = try {
        withContext(Dispatchers.IO) { block() }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.error("GCS 오류 ({}) path={}: {}", context, gcsPath, e.message, e)
        throw BusinessException(ErrorCode.STORAGE_UNAVAILABLE, e)
    }

    /** GCS 경로를 받아 V4 signed URL을 발급한다 (기본 1시간 유효). 이미지·동영상 등 파일 종류에 관계없이 재사용 가능. */
    suspend fun generateSignedUrl(gcsPath: String, expiryHours: Long = 1L): String {
        return guardGcs("조회 URL 서명", gcsPath) {
            val blobInfo = BlobInfo.newBuilder(BlobId.of(bucketName, gcsPath)).build()
            gcsStorage.signUrl(
                blobInfo,
                expiryHours,
                TimeUnit.HOURS,
                Storage.SignUrlOption.withV4Signature()
            ).toString()
        }
    }

    /**
     * GCS 경로에 대해 mp4 업로드용 V4 signed PUT URL을 발급한다 (기본 10분 유효).
     * Content-Type을 서명 헤더에 포함시켜, 업로드 요청이 정확히 "video/mp4" 헤더를 보내야만 서명이 유효하도록 제한한다.
     * V4에서는 withContentType()이 서명 헤더에 들어가지 않으므로(X-Goog-SignedHeaders=host) withExtHeaders로 명시한다.
     */
    suspend fun generateSignedUploadUrl(gcsPath: String, expiryMinutes: Long = 10L): String {
        return guardGcs("업로드 URL 서명", gcsPath) {
            val blobInfo = BlobInfo.newBuilder(BlobId.of(bucketName, gcsPath)).build()
            gcsStorage.signUrl(
                blobInfo,
                expiryMinutes,
                TimeUnit.MINUTES,
                Storage.SignUrlOption.withV4Signature(),
                Storage.SignUrlOption.httpMethod(HttpMethod.PUT),
                Storage.SignUrlOption.withExtHeaders(mapOf("Content-Type" to UPLOAD_CONTENT_TYPE))
            ).toString()
        }
    }

    /** GCS 경로에 실제 객체가 존재하는지 확인한다 — 업로드 완료 콜백이 클라이언트 주장만으로 video_url을 채우지 않도록 검증용. */
    suspend fun blobExists(gcsPath: String): Boolean {
        return guardGcs("객체 확인", gcsPath) {
            gcsStorage.get(BlobId.of(bucketName, gcsPath)) != null
        }
    }

    /**
     * GCS 경로의 blob을 삭제한다. 존재하지 않으면 false.
     * 개인정보보호법 제21조 파기 의무: 회원탈퇴 시 GCS 라이프사이클(fall-videos/ 30일)을 기다리지 않고 즉시 파기하기 위해 사용.
     */
    suspend fun deleteBlob(gcsPath: String): Boolean {
        return guardGcs("객체 삭제", gcsPath) {
            gcsStorage.delete(BlobId.of(bucketName, gcsPath))
        }
    }

    companion object {
        /** 업로드 URL 서명에 넣는 Content-Type — 앱은 응답 content_type 값을 그대로 헤더로 보낸다(FallLogController). */
        const val UPLOAD_CONTENT_TYPE = "video/mp4"
    }
}
