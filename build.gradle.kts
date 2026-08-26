import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("org.springframework.boot") version "3.4.4"
    id("io.spring.dependency-management") version "1.1.7"
    kotlin("jvm") version "2.1.20"
    kotlin("plugin.spring") version "2.1.20"
}

group = "com.onsafe"
version = "0.0.1-SNAPSHOT"

java {
    sourceCompatibility = JavaVersion.VERSION_17
}

repositories {
    mavenCentral()
}

dependencies {
    // Spring WebFlux (리액티브 웹)
    implementation("org.springframework.boot:spring-boot-starter-webflux")

    // Kotlin 코루틴 (WebFlux와 함께 사용)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor")
    implementation("io.projectreactor.kotlin:reactor-kotlin-extensions")

    // Kotlin 기본
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")

    // Firebase Admin SDK (Firestore + FCM)
    // grpc-netty-shaded: Reactor Netty 와 Netty 버전 충돌 → 제외
    // grpc-okhttp: OkHttp 기반 대체 전송 구현체 (Netty 미사용, 충돌 없음) → 명시 추가
    implementation("com.google.firebase:firebase-admin:9.3.0") {
        exclude(group = "io.grpc", module = "grpc-netty-shaded")
    }
    runtimeOnly("io.grpc:grpc-okhttp:1.62.2")

    // Spring Security (JWT 인증)
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("io.jsonwebtoken:jjwt-api:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.6")

    // AWS SDK v2 — SES 이메일 발송
    implementation(platform("software.amazon.awssdk:bom:2.25.0"))
    implementation("software.amazon.awssdk:sesv2")

    // CompletableFuture.await() — SesAsyncClient 코루틴 연동
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-jdk8")

    // 유효성 검사
    implementation("org.springframework.boot:spring-boot-starter-validation")

    // Redis (인증코드 임시 저장 — TTL 자동 만료)
    implementation("org.springframework.boot:spring-boot-starter-data-redis-reactive")

    // SpringDoc OpenAPI (Swagger UI)
    implementation("org.springdoc:springdoc-openapi-starter-webflux-ui:2.8.0")

    // 테스트
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("io.projectreactor:reactor-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("io.mockk:mockk:1.13.10")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test")
}

tasks.withType<KotlinCompile> {
    compilerOptions {
        freeCompilerArgs.add("-Xjsr305=strict")
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}