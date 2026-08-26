package com.onsafe.backend.domain.settings

import com.onsafe.backend.domain.settings.model.dto.NotificationSettingsRequest
import com.onsafe.backend.domain.settings.model.entity.UserSettings
import com.onsafe.backend.domain.settings.repository.SettingsRepository
import com.onsafe.backend.domain.settings.service.SettingsService
import com.onsafe.backend.domain.user.repository.UserRepository
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class SettingsServiceTest {

    private val settingsRepository: SettingsRepository = mockk()
    private val userRepository: UserRepository = mockk()
    private lateinit var settingsService: SettingsService

    private val baseSettings = UserSettings(
        userId = "testUser",
        notificationEnabled = true,
        soundEnabled = true,
        vibrationEnabled = true,
    )

    @BeforeEach
    fun setUp() {
        settingsService = SettingsService(settingsRepository, userRepository)
    }

    @Test
    fun `알림 설정 조회 - soundEnabled와 vibrationEnabled가 응답에 포함된다`() = runTest {
        coEvery { settingsRepository.findByUserId("testUser") } returns baseSettings

        val result = settingsService.getNotificationSettings("testUser")

        assertTrue(result.notificationEnabled)
        assertTrue(result.soundEnabled)
        assertTrue(result.vibrationEnabled)
    }

    @Test
    fun `알림 설정 변경 - 소리 토글 OFF 저장된다`() = runTest {
        val savedSlot = slot<UserSettings>()
        coEvery { settingsRepository.findByUserId("testUser") } returns baseSettings
        coEvery { settingsRepository.save(capture(savedSlot)) } coAnswers { savedSlot.captured }

        val result = settingsService.updateNotifications("testUser", NotificationSettingsRequest(soundEnabled = false))

        assertFalse(result.soundEnabled)
        assertTrue(result.vibrationEnabled)
        assertTrue(result.notificationEnabled)
    }

    @Test
    fun `알림 설정 변경 - 진동 토글 OFF 저장된다`() = runTest {
        val savedSlot = slot<UserSettings>()
        coEvery { settingsRepository.findByUserId("testUser") } returns baseSettings
        coEvery { settingsRepository.save(capture(savedSlot)) } coAnswers { savedSlot.captured }

        val result = settingsService.updateNotifications("testUser", NotificationSettingsRequest(vibrationEnabled = false))

        assertTrue(result.soundEnabled)
        assertFalse(result.vibrationEnabled)
    }

    @Test
    fun `알림 설정 변경 - 전체 알림 OFF 시 소리 진동도 함께 OFF된다`() = runTest {
        val savedSlot = slot<UserSettings>()
        coEvery { settingsRepository.findByUserId("testUser") } returns baseSettings
        coEvery { settingsRepository.save(capture(savedSlot)) } coAnswers { savedSlot.captured }

        val request = NotificationSettingsRequest(
            notificationEnabled = false,
            soundEnabled = false,
            vibrationEnabled = false
        )
        val result = settingsService.updateNotifications("testUser", request)

        assertFalse(result.notificationEnabled)
        assertFalse(result.soundEnabled)
        assertFalse(result.vibrationEnabled)
    }

    @Test
    fun `알림 설정 변경 - 변경하지 않은 필드는 기존값 유지된다`() = runTest {
        val savedSlot = slot<UserSettings>()
        coEvery { settingsRepository.findByUserId("testUser") } returns baseSettings
        coEvery { settingsRepository.save(capture(savedSlot)) } coAnswers { savedSlot.captured }

        val result = settingsService.updateNotifications("testUser", NotificationSettingsRequest(notificationEnabled = false))

        assertFalse(result.notificationEnabled)
        assertTrue(result.soundEnabled)
        assertTrue(result.vibrationEnabled)
    }

    @Test
    fun `알림 설정 변경 - 설정이 없으면 기본값으로 생성 후 수정된다`() = runTest {
        val savedSlot = slot<UserSettings>()
        coEvery { settingsRepository.findByUserId("testUser") } returns null
        coEvery { userRepository.existsByUserId("testUser") } returns true
        coEvery { settingsRepository.save(capture(savedSlot)) } coAnswers { savedSlot.captured }

        val result = settingsService.updateNotifications("testUser", NotificationSettingsRequest(soundEnabled = false))

        assertFalse(result.soundEnabled)
        assertTrue(result.notificationEnabled)
        assertTrue(result.vibrationEnabled)
    }
}
