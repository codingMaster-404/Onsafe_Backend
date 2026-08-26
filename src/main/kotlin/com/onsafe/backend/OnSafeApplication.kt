package com.onsafe.backend

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableScheduling  // FallLogEscalationScheduler — 미확인 위험 이벤트 재알림
class OnSafeApplication

fun main(args: Array<String>) {
    runApplication<OnSafeApplication>(*args)
}