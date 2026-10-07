package com.onsafe.backend.common.livekit

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class LiveKitRoomServiceTest {

    @Test
    fun `앱 접속 주소(wss)를 서버 API 주소(https)로 바꾼다`() {
        assertEquals("https://onsafe.livekit.cloud", LiveKitRoomService.apiHost("wss://onsafe.livekit.cloud"))
        assertEquals("http://localhost:7880", LiveKitRoomService.apiHost("ws://localhost:7880"))
        assertEquals("https://already.https", LiveKitRoomService.apiHost("https://already.https"))
    }

    @Test
    fun `설정이 비어 있으면 아무것도 하지 않고 false`() = runTest {
        assertFalse(LiveKitRoomService("", "", "").deleteRoom("live-elder1"))
    }

    @Test
    fun `설정이 비어 있으면 방 목록은 null`() = runTest {
        org.junit.jupiter.api.Assertions.assertNull(LiveKitRoomService("", "", "").listRoomNames())
    }
}
