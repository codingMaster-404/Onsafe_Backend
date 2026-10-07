package com.onsafe.backend.common.livekit

import com.onsafe.backend.common.exception.BusinessException
import com.onsafe.backend.common.exception.ErrorCode
import io.livekit.server.AccessToken
import io.livekit.server.CanPublish
import io.livekit.server.CanPublishData
import io.livekit.server.CanSubscribe
import io.livekit.server.RoomJoin
import io.livekit.server.RoomName
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * 보호자 실시간 영상(LIVE)용 LiveKit 접속 토큰 발급.
 *
 * 방은 피보호자 1명당 1개(`live-{elderUserId}`)이고, 권한을 역할별로 나눈다 —
 * 보호자는 구독만(시청 전용), 피보호자는 송출만. 토큰이 새도 반대 권한으로는 쓸 수 없다.
 * LiveKit은 토큰 유효시간을 방 입장 시점에만 검사하므로 세션 최대 시간은 서버가 방을 지워 강제한다(W3).
 *
 * 키가 비어 있으면(로컬·미설정 환경) LIVE 요청만 [ErrorCode.LIVE_UNAVAILABLE]로 거절하고 앱 기동은 막지 않는다 —
 * 선택 기능이라 미설정이 다른 기능을 멈추게 하면 안 된다.
 */
@Component
class LiveKitTokenIssuer(
    @Value("\${livekit.url:}") val serverUrl: String,
    @Value("\${livekit.api-key:}") private val apiKey: String,
    @Value("\${livekit.api-secret:}") private val apiSecret: String
) {

    fun viewerToken(elderUserId: String, guardianUserId: String, ttl: Duration): String =
        issue(identity = "guardian-$guardianUserId", elderUserId = elderUserId, ttl = ttl, publish = false)

    fun publisherToken(elderUserId: String, ttl: Duration): String =
        issue(identity = "elder-$elderUserId", elderUserId = elderUserId, ttl = ttl, publish = true)

    private fun issue(identity: String, elderUserId: String, ttl: Duration, publish: Boolean): String {
        if (serverUrl.isBlank() || apiKey.isBlank() || apiSecret.isBlank()) {
            throw BusinessException(ErrorCode.LIVE_UNAVAILABLE)
        }
        return try {
            AccessToken(apiKey, apiSecret).apply {
                this.identity = identity
                this.ttl = ttl.toMillis()
                addGrants(
                    RoomJoin(true),
                    RoomName(roomName(elderUserId)),
                    CanPublish(publish),
                    CanSubscribe(!publish),
                    CanPublishData(false)
                )
            }.toJwt()
        } catch (e: Exception) {
            throw BusinessException(ErrorCode.LIVE_UNAVAILABLE, e)
        }
    }

    companion object {
        fun roomName(elderUserId: String) = "live-$elderUserId"
    }
}
