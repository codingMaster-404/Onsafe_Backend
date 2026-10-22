package com.onsafe.backend.domain.notification

import com.onsafe.backend.domain.notification.service.CHANNEL_ALERTS
import com.onsafe.backend.domain.notification.service.CHANNEL_DANGER
import com.onsafe.backend.domain.notification.service.CHANNEL_PAIRING
import com.onsafe.backend.domain.notification.service.CHANNEL_WARNING
import com.onsafe.backend.domain.notification.service.channelFor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

// 알림 채널 판정 — 앱 PushNotifications.fallAlertLevel과 같은 규칙인지 확인한다(N1).
class NotificationChannelTest {

    @Test
    fun `채널 ID는 앱 값과 같다`() {
        // 앱이 _v 번호를 올리면 이 테스트와 함께 바꾼다.
        assertEquals("onsafe_danger_v1", CHANNEL_DANGER)
        assertEquals("onsafe_warning_v1", CHANNEL_WARNING)
        assertEquals("onsafe_alerts", CHANNEL_ALERTS)
        assertEquals("onsafe_pairing", CHANNEL_PAIRING)
    }

    @Test
    fun `미확인 위험 재알림은 점수와 무관하게 위험`() {
        assertEquals(CHANNEL_DANGER, channelFor("fall_escalated", fall = false, score = 10f))
    }

    @Test
    fun `낙상이면 점수와 무관하게 위험`() {
        assertEquals(CHANNEL_DANGER, channelFor("fall_detected", fall = true, score = 40f))
    }

    @Test
    fun `점수가 75를 넘으면 위험, 75 이하는 주의`() {
        assertEquals(CHANNEL_DANGER, channelFor("fall_detected", fall = false, score = 75.1f))
        // 경계값은 strict — 75는 주의(RiskLevel.DANGER_THRESHOLD와 같은 규칙)
        assertEquals(CHANNEL_WARNING, channelFor("fall_detected", fall = false, score = 75f))
        assertEquals(CHANNEL_WARNING, channelFor("fall_detected", fall = false, score = 60f))
    }

    @Test
    fun `낙상 계열인데 점수가 없으면 놓치지 않도록 위험`() {
        assertEquals(CHANNEL_DANGER, channelFor("fall_detected", fall = false, score = null))
    }

    @Test
    fun `낙상 계열이 아니면 기존 채널 그대로`() {
        assertEquals(CHANNEL_PAIRING, channelFor("pairing_approved", fall = false, score = null))
        assertEquals(CHANNEL_ALERTS, channelFor("camera_offline", fall = false, score = null))
        assertEquals(CHANNEL_ALERTS, channelFor("camera_stream_down", fall = false, score = null))
        assertEquals(CHANNEL_ALERTS, channelFor(null, fall = false, score = null))
        // event 없이 fall·score만 있어도 낙상 채널로 보내지 않는다 — 앱도 event로 낙상 계열을 가른다
        assertEquals(CHANNEL_ALERTS, channelFor(null, fall = true, score = 90f))
    }
}
