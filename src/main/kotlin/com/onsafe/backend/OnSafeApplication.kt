package com.onsafe.backend

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableScheduling  // FallLogVideoReconciliationJob — 다른 내부 잡은 Cloud Scheduler(/internal/jobs/*)가 트리거한다
class OnSafeApplication

fun main(args: Array<String>) {
    runApplication<OnSafeApplication>(*args)
}