package com.onsafe.backend.common.livekit

import io.livekit.server.RoomServiceClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * LiveKit 방 API — 실시간 영상 세션을 서버가 강제로 끝낼 때 방을 지운다(접속자 전원 퇴장).
 * LiveKit 토큰 TTL은 입장 시점에만 검사하므로 이미 들어간 송출·시청은 방을 지워야 끊긴다(X3).
 *
 * 종료는 세션 정리의 마지막 단계라 실패해도 호출부(연결 해제·탈퇴 등)를 막지 않는다 — 예외 대신 false와 로그.
 * 키가 비어 있으면(로컬·미설정) 아무것도 하지 않는다.
 */
@Component
class LiveKitRoomService(
    @Value("\${livekit.url:}") private val serverUrl: String,
    @Value("\${livekit.api-key:}") private val apiKey: String,
    @Value("\${livekit.api-secret:}") private val apiSecret: String
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val client: RoomServiceClient? by lazy {
        if (serverUrl.isBlank() || apiKey.isBlank() || apiSecret.isBlank()) null
        else RoomServiceClient.createClient(apiHost(serverUrl), apiKey, apiSecret)
    }

    /** 방이 없어도(이미 비어 자동 삭제됐거나 아무도 안 들어옴) 성공으로 본다. */
    suspend fun deleteRoom(room: String): Boolean {
        val client = client ?: return false
        return try {
            val response = withContext(Dispatchers.IO) { client.deleteRoom(room).execute() }
            val ok = response.isSuccessful || response.code() == 404
            if (!ok) log.warn("LiveKit 방 삭제 실패 — room={}, status={}", room, response.code())
            ok
        } catch (e: Exception) {
            log.warn("LiveKit 방 삭제 실패 — room={}: {}", room, e.message)
            false
        }
    }

    /** 현재 열린 방 이름 목록. 설정이 없거나 조회에 실패하면 null(정리 잡이 이번 회차를 건너뛴다). */
    suspend fun listRoomNames(): List<String>? {
        val client = client ?: return null
        return try {
            val response = withContext(Dispatchers.IO) { client.listRooms().execute() }
            if (!response.isSuccessful) {
                log.warn("LiveKit 방 목록 조회 실패 — status={}", response.code())
                return null
            }
            response.body().orEmpty().map { it.name }
        } catch (e: Exception) {
            log.warn("LiveKit 방 목록 조회 실패: {}", e.message)
            null
        }
    }

    companion object {
        // 앱 접속 주소는 wss://, 서버 API(Twirp)는 같은 호스트의 https://
        fun apiHost(serverUrl: String): String = when {
            serverUrl.startsWith("wss://") -> "https://" + serverUrl.removePrefix("wss://")
            serverUrl.startsWith("ws://") -> "http://" + serverUrl.removePrefix("ws://")
            else -> serverUrl
        }
    }
}
