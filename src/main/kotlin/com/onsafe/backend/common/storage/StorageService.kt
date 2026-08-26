package com.onsafe.backend.common.storage

import com.google.auth.oauth2.ServiceAccountCredentials
import com.google.cloud.storage.BlobId
import com.google.cloud.storage.BlobInfo
import com.google.cloud.storage.HttpMethod
import com.google.cloud.storage.Storage
import com.google.cloud.storage.StorageOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.io.FileInputStream
import java.util.concurrent.TimeUnit

@Service
class StorageService(
    @Value("\${firebase.credentials:serviceAccountKey.json}") private val credentialsPath: String,
    @Value("\${firebase.storage.bucket:}") private val bucketName: String
) {
    private val gcsStorage: Storage by lazy {
        val credentials = ServiceAccountCredentials.fromStream(FileInputStream(credentialsPath))
        StorageOptions.newBuilder()
            .setCredentials(credentials)
            .build()
            .service
    }

    /** GCS 경로를 받아 V4 signed URL을 발급한다 (기본 1시간 유효). 이미지·동영상 등 파일 종류에 관계없이 재사용 가능. */
    suspend fun generateSignedUrl(gcsPath: String, expiryHours: Long = 1L): String {
        return withContext(Dispatchers.IO) {
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
     * Content-Type을 서명에 포함시켜, 업로드 요청이 정확히 "video/mp4" 헤더를 보내야만 서명이 유효하도록 제한한다.
     */
    suspend fun generateSignedUploadUrl(gcsPath: String, expiryMinutes: Long = 10L): String {
        return withContext(Dispatchers.IO) {
            val blobInfo = BlobInfo.newBuilder(BlobId.of(bucketName, gcsPath))
                .setContentType("video/mp4")
                .build()
            gcsStorage.signUrl(
                blobInfo,
                expiryMinutes,
                TimeUnit.MINUTES,
                Storage.SignUrlOption.withV4Signature(),
                Storage.SignUrlOption.httpMethod(HttpMethod.PUT),
                Storage.SignUrlOption.withContentType()
            ).toString()
        }
    }

    /** GCS 경로에 실제 객체가 존재하는지 확인한다 — 업로드 완료 콜백이 클라이언트 주장만으로 video_url을 채우지 않도록 검증용. */
    suspend fun blobExists(gcsPath: String): Boolean {
        return withContext(Dispatchers.IO) {
            gcsStorage.get(BlobId.of(bucketName, gcsPath)) != null
        }
    }

    /**
     * GCS 경로의 blob을 삭제한다. 존재하지 않으면 false.
     * 개인정보보호법 제21조 파기 의무: 회원탈퇴 시 GCS 라이프사이클(최대 180일)을 기다리지 않고 즉시 파기하기 위해 사용.
     */
    suspend fun deleteBlob(gcsPath: String): Boolean {
        return withContext(Dispatchers.IO) {
            gcsStorage.delete(BlobId.of(bucketName, gcsPath))
        }
    }
}
