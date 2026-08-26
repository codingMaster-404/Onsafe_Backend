package com.onsafe.backend.domain.user

import com.onsafe.backend.common.exception.BusinessException
import com.onsafe.backend.common.storage.StorageService
import com.onsafe.backend.domain.auth.repository.LoginHistoryRepository
import com.onsafe.backend.domain.camera.repository.RealtimeDataRepository
import com.onsafe.backend.domain.guardian.repository.GuardianLinkRepository
import com.onsafe.backend.domain.logs.repository.FallLogRepository
import com.onsafe.backend.domain.notification.repository.NotificationRepository
import com.onsafe.backend.domain.settings.repository.SettingsRepository
import com.onsafe.backend.domain.user.model.dto.UserUpdateRequest
import com.onsafe.backend.domain.user.model.entity.User
import com.onsafe.backend.domain.user.repository.UserRepository
import com.onsafe.backend.domain.user.service.UserService
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import java.time.LocalDateTime

class UserServiceTest {

    private val userRepository: UserRepository = mockk()
    private val settingsRepository: SettingsRepository = mockk()
    private val fallLogRepository: FallLogRepository = mockk()
    private val loginHistoryRepository: LoginHistoryRepository = mockk()
    private val realtimeDataRepository: RealtimeDataRepository = mockk()
    private val storageService: StorageService = mockk()
    private val notificationRepository: NotificationRepository = mockk()
    private val guardianLinkRepository: GuardianLinkRepository = mockk()
    private val passwordEncoder = BCryptPasswordEncoder()
    private lateinit var userService: UserService

    private val baseUser = User(
        userId = "testUser",
        password = BCryptPasswordEncoder().encode("oldPass"),
        name = "홍길동",
        phone = "010-1234-5678",
        mail = "test@test.com",
        address = null,
        addressDetail = null,
        createdAt = LocalDateTime.now()
    )

    @BeforeEach
    fun setUp() {
        userService = UserService(
            userRepository, settingsRepository, passwordEncoder, fallLogRepository,
            loginHistoryRepository, realtimeDataRepository, storageService,
            notificationRepository, guardianLinkRepository
        )
    }

    @Test
    fun `주소 수정 - address와 addressDetail이 정상 저장된다`() = runTest {
        val savedSlot = slot<User>()
        coEvery { userRepository.findByUserId("testUser") } returns baseUser
        coEvery { userRepository.save(capture(savedSlot)) } coAnswers { savedSlot.captured }

        val result = userService.updateUser("testUser", UserUpdateRequest(address = "서울시 강남구 테헤란로", addressDetail = "101호"))

        assertEquals("서울시 강남구 테헤란로", result.address)
        assertEquals("101호", result.addressDetail)
    }

    @Test
    fun `주소 수정 - addressDetail 없이 address만 변경된다`() = runTest {
        val savedSlot = slot<User>()
        coEvery { userRepository.findByUserId("testUser") } returns baseUser
        coEvery { userRepository.save(capture(savedSlot)) } coAnswers { savedSlot.captured }

        val result = userService.updateUser("testUser", UserUpdateRequest(address = "부산시 해운대구"))

        assertEquals("부산시 해운대구", result.address)
        assertNull(result.addressDetail)
    }

    @Test
    fun `비밀번호 변경 - currentPassword 일치 시 정상 변경된다`() = runTest {
        val savedSlot = slot<User>()
        coEvery { userRepository.findByUserId("testUser") } returns baseUser
        coEvery { userRepository.save(capture(savedSlot)) } coAnswers { savedSlot.captured }

        val result = userService.updateUser("testUser", UserUpdateRequest(currentPassword = "oldPass", password = "newPass1234"))

        assertNotNull(result)
    }

    @Test
    fun `비밀번호 변경 - currentPassword 불일치 시 예외 발생`() = runTest {
        coEvery { userRepository.findByUserId("testUser") } returns baseUser

        val thrown = runCatching {
            userService.updateUser("testUser", UserUpdateRequest(currentPassword = "wrongPass", password = "newPass1234"))
        }.exceptionOrNull()

        assertNotNull(thrown)
        assertTrue(thrown is BusinessException)
    }

    @Test
    fun `비밀번호 변경 - currentPassword 없이 password만 전달 시 예외 발생`() = runTest {
        coEvery { userRepository.findByUserId("testUser") } returns baseUser

        val thrown = runCatching {
            userService.updateUser("testUser", UserUpdateRequest(password = "newPass1234"))
        }.exceptionOrNull()

        assertNotNull(thrown)
        assertTrue(thrown is BusinessException)
    }

    @Test
    fun `프로필 조회 - UserResponse에 address와 addressDetail이 포함된다`() = runTest {
        coEvery { userRepository.findByUserId("testUser") } returns baseUser.copy(address = "서울시 마포구", addressDetail = "202호")

        val result = userService.getUser("testUser")

        assertEquals("서울시 마포구", result.address)
        assertEquals("202호", result.addressDetail)
    }
}
