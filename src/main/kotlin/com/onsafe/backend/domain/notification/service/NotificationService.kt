package com.onsafe.backend.domain.notification.service

import com.google.firebase.messaging.AndroidConfig
import com.google.firebase.messaging.AndroidNotification
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingException
import com.google.firebase.messaging.MessagingErrorCode
import com.google.firebase.messaging.MulticastMessage
import com.google.firebase.messaging.Notification as FcmNotification
import com.onsafe.backend.common.exception.BusinessException
import com.onsafe.backend.common.exception.ErrorCode
import com.onsafe.backend.common.util.await
import com.onsafe.backend.domain.guardian.repository.GuardianLinkRepository
import com.onsafe.backend.domain.notification.model.dto.NotificationLogResponse
import com.onsafe.backend.domain.notification.model.dto.NotificationRequest
import com.onsafe.backend.domain.notification.model.dto.NotificationResponse
import com.onsafe.backend.domain.notification.model.entity.Notification
import com.onsafe.backend.domain.notification.repository.FcmTokenRepository
import com.onsafe.backend.domain.notification.repository.NotificationRepository
import com.onsafe.backend.domain.user.model.entity.User
import com.onsafe.backend.domain.user.repository.UserRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

// 앱(PushNotifications.kt)이 만든 알림 채널 ID. 서버가 android.notification.channel_id로 실어
// 보내야 백그라운드 알림에도 채널이 적용된다.
private const val CHANNEL_ALERTS = "onsafe_alerts"   // 낙상·오프라인 등 즉시 확인이 필요한 알림
private const val CHANNEL_PAIRING = "onsafe_pairing" // 보호자 연결 상태 변화
private const val EVENT_KEY = "event"
private const val PAIRING_EVENT_PREFIX = "pairing"

@Service
class NotificationService(
    private val userRepository: UserRepository,
    private val notificationRepository: NotificationRepository,
    private val guardianLinkRepository: GuardianLinkRepository,
    private val fcmTokenRepository: FcmTokenRepository
) {

    private val log = LoggerFactory.getLogger(javaClass)

    suspend fun sendNotification(request: NotificationRequest): NotificationResponse {
        val user = userRepository.findByUserId(request.userId)
            ?: throw BusinessException(ErrorCode.USER_NOT_FOUND)
        return sendNotification(user, request)
    }

    // 이미 조회해둔 User가 있는 호출부(notifyElderAndGuardians)가 같은 유저를 다시 조회하지
    // 않도록 분리한 내부 버전. 공개 API인 sendNotification(request)는 여전히 직접 조회한다.
    private suspend fun sendNotification(user: User, request: NotificationRequest): NotificationResponse {
        // FCM 발송 성공/실패/토큰 없음과 무관하게 알림 목록에는 항상 남긴다 — 목록 API가
        // push 전송 결과에 의존하지 않고 "무슨 알림이 발생했는지"를 그대로 반영해야 하기 때문.
        notificationRepository.save(
            Notification(
                userId = request.userId,
                title = request.title,
                body = request.body,
                logId = request.logId,
                score = request.score,
                fall = request.fall,
            )
        )

        // 기기별 토큰 전부에 보낸다(B8). 예전에는 계정당 토큰 1개라 마지막에 앱을 켠 기기만 받았다.
        val tokens = runCatching { fcmTokenRepository.findAll(user.userId) }
            .onFailure { e -> log.warn("FCM 토큰 조회 실패 (userId: ${request.userId}): ${e.message}") }
            .getOrDefault(emptyList())
        if (tokens.isEmpty()) {
            return NotificationResponse(status = "ok", message = "FCM 토큰이 없습니다.", fcmMessageId = "")
        }

        val messageBuilder = MulticastMessage.builder()
            .addAllTokens(tokens.map { it.token })
            .setNotification(
                FcmNotification.builder()
                    .setTitle(request.title)
                    .setBody(request.body)
                    .build()
            )
            // 앱이 백그라운드·종료 상태면 onMessageReceived가 호출되지 않고 시스템이 알림을 직접
            // 표시한다. 이때 channel_id가 없으면 앱이 만든 채널(onsafe_alerts: IMPORTANCE_HIGH)이
            // 적용되지 않아 낙상 알림이 소리·헤드업 없이 조용히 쌓인다. 서버가 채널을 지정해야 한다.
            .setAndroidConfig(androidConfigFor(request.data?.get(EVENT_KEY)))
        request.data?.forEach { (k, v) -> messageBuilder.putData(k, v) }

        val batch = try {
            FirebaseMessaging.getInstance().sendEachForMulticastAsync(messageBuilder.build()).await()
        } catch (e: Exception) {
            log.warn("FCM 전송 실패 (userId: ${request.userId}): ${e.message}")
            throw BusinessException(ErrorCode.FCM_SEND_FAILED, e)
        }

        // 토큰별 결과를 받아 만료된 기기만 정리한다 — 예전에는 필드가 하나뿐이라 실패 한 번에
        // 그 계정의 유일한 토큰이 지워지고 재등록 전까지 전면 미수신이 됐다.
        batch.responses.forEachIndexed { index, result ->
            if (result.isSuccessful) return@forEachIndexed
            val token = tokens[index]
            val errorCode = (result.exception as? FirebaseMessagingException)?.messagingErrorCode
            if (errorCode == MessagingErrorCode.UNREGISTERED) {
                log.warn("FCM 토큰 만료로 삭제 (userId: ${request.userId}, deviceId: ${token.deviceId})")
                runCatching { fcmTokenRepository.deleteByToken(request.userId, token.token) }
                    .onFailure { e -> log.warn("만료 토큰 삭제 실패 (userId: ${request.userId}): ${e.message}") }
            } else {
                log.warn("FCM 전송 실패 (userId: ${request.userId}, deviceId: ${token.deviceId}): $errorCode")
            }
        }

        if (batch.successCount == 0) throw BusinessException(ErrorCode.FCM_SEND_FAILED)
        val messageId = batch.responses.firstOrNull { it.isSuccessful }?.messageId ?: ""
        return NotificationResponse(
            status = "ok",
            message = "알림 전송 완료 (${batch.successCount}/${tokens.size})",
            fcmMessageId = messageId
        )
    }

    // 앱이 만든 채널 ID와 반드시 같아야 한다(프론트 PushNotifications.CHANNEL_*).
    private fun androidConfigFor(event: String?): AndroidConfig {
        val channelId = if (event?.startsWith(PAIRING_EVENT_PREFIX) == true) CHANNEL_PAIRING else CHANNEL_ALERTS
        return AndroidConfig.builder()
            .setPriority(AndroidConfig.Priority.HIGH)
            .setNotification(AndroidNotification.builder().setChannelId(channelId).build())
            .build()
    }

    /** FCM 토큰 등록 — 같은 기기가 앱을 켤 때마다 호출되므로 deviceId 기준 upsert. */
    suspend fun registerFcmToken(userId: String, fcmToken: String, deviceId: String) {
        userRepository.findByUserId(userId) ?: throw BusinessException(ErrorCode.USER_NOT_FOUND)
        fcmTokenRepository.upsert(userId, deviceId, fcmToken)
    }

    /** FCM 토큰 해제 — 로그아웃·탈퇴 시 그 기기 것만 지운다(다른 기기는 그대로). */
    suspend fun unregisterFcmToken(userId: String, fcmToken: String, deviceId: String) {
        fcmTokenRepository.delete(userId, deviceId, fcmToken)
    }

    /**
     * 피보호자 본인 + 연결된 보호자 전원에게 알림을 보낸다. 발송 대상별로 개별 실패를 격리해
     * 한 명(예: 만료된 FCM 토큰) 실패가 나머지 수신자 발송을 막지 않게 한다. 본인 발송(FCM 네트워크
     * 호출 포함)과 보호자 목록 조회·보호자 발송은 서로 데이터 의존이 없어 전부 병렬로 처리해
     * 보호자 수·본인 발송 지연에 비례해 전체 시간이 늘어나지 않게 한다.
     */
    suspend fun notifyElderAndGuardians(
        elderUserId: String,
        title: String,
        body: String,
        logId: String? = null,
        score: Float? = null,
        fall: Boolean = false,
        data: Map<String, String>? = null
    ) {
        coroutineScope {
            val elderDeferred = async { userRepository.findByUserId(elderUserId) }

            val elderSend = async {
                runCatching {
                    val elder = elderDeferred.await() ?: throw BusinessException(ErrorCode.USER_NOT_FOUND)
                    sendNotification(elder, NotificationRequest(elderUserId, title, body, logId, score, fall, data))
                }.onFailure { e -> log.warn("알림 전송 실패 (userId: $elderUserId): ${e.message}") }
            }

            // 보호자 목록 조회와 elder 조회 실패를 여기서 직접 await()하면 coroutineScope의
            // 구조적 동시성 때문에 이미 실행 중인 elderSend까지 취소되며 함수 전체가 예외를 던진다 —
            // "피보호자 본인 발송은 보호자 쪽 실패와 무관하게 항상 이뤄져야 한다"는 이 함수의 격리
            // 원칙이 깨진다. runCatching으로 감싸 실패해도 빈 목록/폴백 이름으로 계속 진행한다.
            val guardianIds = runCatching { guardianLinkRepository.findGuardiansOf(elderUserId) }
                .onFailure { e -> log.warn("보호자 목록 조회 실패 (elderUserId: $elderUserId): ${e.message}") }
                .getOrDefault(emptyList())
            val elderName = runCatching { elderDeferred.await() }.getOrNull()?.name ?: elderUserId

            val guardianSends = guardianIds.map { guardianId ->
                async {
                    runCatching {
                        sendNotification(NotificationRequest(guardianId, title, "[$elderName] $body", logId, score, fall, data))
                    }.onFailure { e -> log.warn("보호자 알림 전송 실패 (guardianId: $guardianId): ${e.message}") }
                }
            }

            (listOf(elderSend) + guardianSends).awaitAll()
        }
    }

    // 카메라 앱 오프라인 감지 시 연결된 보호자 전원에게 발송. HeartbeatWatchdogJob 이 호출한다.
    // notifyElderAndGuardians 와 달리 피보호자 본인에게는 안 보낸다 — 앱이 오프라인이라 어차피
    // 못 받을 뿐 아니라, 복구 시 자기 화면에 상태가 바로 반영되므로 별도 알림이 불필요.
    // 개별 발송 실패 격리 원칙은 동일 — 한 명 실패가 나머지 보호자 발송을 막지 않는다.
    suspend fun notifyGuardiansCameraOffline(elderUserId: String) {
        val elderName = runCatching { userRepository.findByUserId(elderUserId) }.getOrNull()?.name ?: elderUserId
        val guardianIds = runCatching { guardianLinkRepository.findGuardiansOf(elderUserId) }
            .onFailure { e -> log.warn("보호자 목록 조회 실패 (elderUserId: $elderUserId): ${e.message}") }
            .getOrDefault(emptyList())
        if (guardianIds.isEmpty()) {
            log.info("오프라인 알림 대상 보호자 없음 — elderUserId=$elderUserId")
            return
        }
        coroutineScope {
            guardianIds.map { guardianId ->
                async {
                    runCatching {
                        sendNotification(
                            NotificationRequest(
                                userId = guardianId,
                                title = "카메라 오프라인",
                                body = "[$elderName] 카메라 앱과 연결이 끊겼습니다. 상태를 확인해주세요.",
                                data = mapOf("event" to "camera_offline", "elder_user_id" to elderUserId)
                            )
                        )
                    }.onFailure { e -> log.warn("오프라인 알림 실패 (guardianId: $guardianId): ${e.message}") }
                }
            }.awaitAll()
        }
    }

    // 오프라인 알림을 받은 뒤 heartbeat 이 다시 재개돼 복구된 경우. 재알림 게이트 초기화는
    // 호출부(HeartbeatWatchdogJob 또는 upsertHeartbeat 후속)에서 별도로 담당.
    suspend fun notifyGuardiansCameraRecovered(elderUserId: String) {
        val elderName = runCatching { userRepository.findByUserId(elderUserId) }.getOrNull()?.name ?: elderUserId
        val guardianIds = runCatching { guardianLinkRepository.findGuardiansOf(elderUserId) }
            .onFailure { e -> log.warn("보호자 목록 조회 실패 (elderUserId: $elderUserId): ${e.message}") }
            .getOrDefault(emptyList())
        if (guardianIds.isEmpty()) return
        coroutineScope {
            guardianIds.map { guardianId ->
                async {
                    runCatching {
                        sendNotification(
                            NotificationRequest(
                                userId = guardianId,
                                title = "카메라 다시 온라인",
                                body = "[$elderName] 카메라 앱 연결이 복구되었습니다.",
                                data = mapOf("event" to "camera_recovered", "elder_user_id" to elderUserId)
                            )
                        )
                    }.onFailure { e -> log.warn("복구 알림 실패 (guardianId: $guardianId): ${e.message}") }
                }
            }.awaitAll()
        }
    }

    suspend fun getNotifications(userId: String): List<NotificationLogResponse> =
        notificationRepository.findRecentByUserId(userId).map { NotificationLogResponse.from(it) }

    suspend fun markRead(userId: String, notificationId: String): NotificationLogResponse {
        val updated = notificationRepository.markRead(notificationId, userId)
            ?: throw BusinessException(ErrorCode.NOTIFICATION_NOT_FOUND)
        return NotificationLogResponse.from(updated)
    }
}
