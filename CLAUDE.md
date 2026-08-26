# OnSafe Backend — CLAUDE.md

## 프로젝트 개요
Spring WebFlux + Kotlin 코루틴 기반 리액티브 백엔드.
Firebase(Firestore, FCM, GCS), AWS SES, Redis, JWT를 외부 서비스로 사용한다.

## 기술 스택
- Spring Boot 3.4.4 / Spring WebFlux
- Kotlin 2.1.20 / 코루틴
- Firebase Admin SDK 9.3.0
- AWS SDK for Java v2 (BOM 2.25.0)
- jjwt 0.12.6
- Redis (Reactive)

---

## AWS SDK 원칙

**SDK 패키지 버전과 서비스 API 버전은 별개다.**

`software.amazon.awssdk` 패키지(SDK v2) 안에도 v1/v2 API 모듈이 공존한다.
신규 AWS 서비스 추가 시 반드시 최신 API 버전 모듈을 선택한다.

```
❌  software.amazon.awssdk:ses      (SES v1 API — 기능 추가 중단)
✅  software.amazon.awssdk:sesv2   (SES v2 API — 현재 표준)
```

**체크리스트 (신규 AWS 서비스 추가 시)**
1. AWS 공식 문서에서 해당 서비스의 최신 API 버전 확인
2. Maven Central에서 모듈명에 버전 접미사(`v2`, `v3` 등) 여부 확인
3. 클라이언트 클래스명으로 교차 검증: `XxxV2Client` 존재 시 그것이 최신

**버전 관리**
- BOM(`software.amazon.awssdk:bom`)을 통해 의존성 버전을 일괄 관리한다.
- BOM 버전은 분기마다 최신으로 업데이트한다 (현재: 2.25.0).

---

## Firebase Admin SDK 원칙

**grpc-netty-shaded 충돌 우회 패턴 — 절대 제거 금지**

```kotlin
// build.gradle.kts
implementation("com.google.firebase:firebase-admin:9.3.0") {
    exclude(group = "io.grpc", module = "grpc-netty-shaded")  // ← 제거 금지
}
runtimeOnly("io.grpc:grpc-okhttp:1.62.2")  // Netty 대신 OkHttp 전송 사용
```

- `grpc-netty-shaded`를 포함하면 Reactor Netty와 Netty 버전 충돌로 런타임 오류 발생
- Firebase Admin SDK 버전 업그레이드 시 `grpc-okhttp` 버전도 함께 호환 여부 확인

**자격증명 파일 로딩**
- `FIREBASE_CREDENTIALS` 환경변수로 경로를 주입한다 (기본값: `serviceAccountKey.json`)
- `serviceAccountKey.json`은 `.gitignore`에 포함되어야 하며 절대 커밋하지 않는다

---

## JWT 원칙

**jjwt 0.12.x API 패턴만 사용한다 — 구버전(0.9.x) 패턴 금지**

```kotlin
// ❌ 0.9.x 구버전 패턴
Jwts.builder().setSubject(email).setExpiration(date)
Jwts.parser().setSigningKey(key)

// ✅ 0.12.x 현재 패턴
Jwts.builder().subject(email).expiration(date)
Jwts.parser().verifyWith(key).build()
```

---

## GCS Signed URL 원칙

**V4 서명만 사용한다 — V2는 deprecated**

```kotlin
// ❌ V2 (deprecated)
Storage.SignUrlOption.withV2Signature()

// ✅ V4 (현재 표준)
Storage.SignUrlOption.withV4Signature()
```

---

## 비동기 프로그래밍 원칙

- 모든 서비스 메서드는 `suspend fun`으로 작성한다.
- Mono/Flux를 서비스 레이어에서 직접 반환하지 않는다.
- Java `CompletableFuture` → 코루틴 전환: `kotlinx-coroutines-jdk8`의 `.await()` 사용
- Firebase SDK 비동기 → 코루틴 전환: `common/util/FirestoreExt.kt`의 `.await()` 확장함수 사용

---

## 예외 처리 원칙

- 외부 SDK 예외는 서비스 레이어에서 반드시 `BusinessException(ErrorCode.XXX)`로 래핑한다.
- SDK별 예외를 구분해서 catch한다:

```kotlin
} catch (e: SdkClientException) {   // 네트워크/연결 실패
    throw BusinessException(ErrorCode.MAIL_SEND_FAILED)
} catch (e: SesV2Exception) {       // SES 발송 거부
    throw BusinessException(ErrorCode.MAIL_SEND_FAILED)
}
```

- 컨트롤러까지 SDK 예외가 전파되어서는 안 된다.

---

## 운영 시 고려사항 체크

코드 수정 시 아래 항목과 연관되는지 먼저 확인하고 언급한다.

| 수정 대상 | 확인 항목 |
|-----------|-----------|
| `/internal/**` 경로 추가·변경 | 외부 접근 가능 여부 — 인프라 레벨 IP 제한 필요 |
| FCM 전송 로직 수정 | 만료 토큰 자동 정리 누락 여부 |
| Redis 키 추가 | TTL 설정 여부 (미설정 시 메모리 누수) |
| GCS·StorageService 수정 | Signed URL TTL(현재 1시간) 영향 여부 |
| AI 서버(`main.py`) 수정 | pkl 파일·`SPRING_EVENT_URL` 환경변수 의존성 영향 여부 |

세부 판단 기준은 메모리 `project_operational_considerations.md` 참고.

---

## 커밋 원칙

- **git 커밋·푸시는 유저가 명시적으로 요청할 때만 진행한다.** 파일 수정 후 자동으로 커밋하지 않는다.
- 테스트 파일(`src/test/**`)은 커밋에 포함하지 않는다. 유저가 명시한 경우만 예외.
- `serviceAccountKey.json` 등 자격증명 파일은 절대 커밋하지 않는다.
