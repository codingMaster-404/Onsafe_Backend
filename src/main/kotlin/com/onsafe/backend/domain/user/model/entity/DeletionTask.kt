package com.onsafe.backend.domain.user.model.entity

import java.time.LocalDateTime

/**
 * 탈퇴 파기 작업(C5). 계정 문서를 먼저 지우므로, 이후 정리에 필요한 값을 이 문서가 들고 있어야 한다
 * — 룩업 키(mail·phone)와 GCS blob 경로에 쓰는 logIds가 계정 문서에만 있었기 때문이다.
 *
 * 문서 ID는 userId다. 가입 트랜잭션이 이 문서의 존재를 확인하므로(UserRepository.createIfNotExists)
 * 파기가 끝나기 전에는 같은 userId로 재가입할 수 없다 — 잡이 새 계정 데이터를 지우는 사고를 막는다.
 */
data class DeletionTask(
    val userId: String,
    val mail: String,
    val phone: String,
    val logIds: List<String> = emptyList(),
    val attempts: Int = 0,
    val lastError: String? = null,
    val createdAt: LocalDateTime = LocalDateTime.now(),
)
