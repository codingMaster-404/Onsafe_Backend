# On-safe-backend

AI 기반 노인 낙상 감지 솔루션의 백엔드 서버

---

## 아키텍처 개요

```
Android App
    │ landmark JSON
    │ WS /ws/stream
    ▼                          Kotlin Spring 서버 (:8080)
Python AI 서버 (:8000)  ──────▶    │ Firestore / Redis / FCM
    │ XGBoost 추론                  ▼
    │ /internal/realtime        Firebase (Firestore·Storage·FCM)
    │ /internal/fall-log
    └──────────────────────────▶
```

- **Python AI 서버 (FastAPI)**: Android on-device MediaPipe → landmark JSON 수신 → 30프레임 슬라이딩 윈도우 → XGBoost 위험도 추론 → Kotlin internal API 호출
- **Kotlin Spring 서버 (WebFlux)**: 앱 API 제공, Firestore 저장, FCM 알림

---

## 기술 스택

| 구분 | 기술 |
|---|---|
| Kotlin 서버 | Spring Boot 3.4, WebFlux, Kotlin Coroutines |
| Python 서버 | FastAPI, XGBoost, scikit-learn |
| 데이터베이스 | Firebase Firestore |
| 캐시·메시징 | Redis (블랙리스트, 재설정·재인증 티켓 TTL, rate limit) |
| 스토리지 | Firebase Storage (GCS) — 낙상 동영상 MP4 클립 |
| 푸시 알림 | Firebase Cloud Messaging (FCM) |
| 인증 | JWT (JJWT 0.12.x) + Redis 블랙리스트 |
| 컨테이너 | Docker Compose (kotlin-api, python-ai, redis) |

---

## 주요 기능

- **낙상 감지 알림**: AI 추론 점수 기반 위험(75 초과)/주의(50 초과~75)/낙상 FCM 알림, 위험 6시간·주의 5분 쿨다운, 15분 sticky floor(위험 진입 시 점수 하락 방지), 2.5초 구간 스무딩
- **낙상 영상 클립**: 위험 등급 이벤트에 한해 4분 mp4 클립을 Android가 GCS에 직접 업로드(signed URL), 서버가 업로드 완료를 재확인 후 반영. 콜백 유실 시 정합성 보정 잡이 자동 복구
- **미확인 위험 이벤트 에스컬레이션**: 확인될 때까지 15분 주기로 재알림
- **비밀번호 재설정**: 아이디·이름·메일 본인확인 후 1회용 재설정 티켓(10분) 발급 — 메일 인증(AWS SES)은 v4.14에서 제거
- **낙상 이력 관리**: 목록·단건 조회·확인·삭제, 동영상 Signed URL 발급
- **설정 관리**: 알림 토글(전체·소리·진동), 마케팅 수신 동의 on/off
- **개인정보 컴플라이언스**: 로그인 이력(감사 로그) 저장, 회원 탈퇴 시 개인정보 즉시 파기(cascade), 낙상 영상 URL AES-256-GCM 암호화

---

## 프로젝트 구조

자세한 파일별 설명은 [`docs/project-structure.md`](docs/project-structure.md) 참조

```
src/main/kotlin/com/onsafe/backend/
├── config/          # Firebase, Redis, Security, Swagger
├── common/          # 예외처리, 응답 래퍼, JWT, Storage, Firestore 확장
└── domain/
    ├── auth/        # 로그인·회원가입·아이디 찾기·비밀번호재설정(본인확인)·로그인 이력
    ├── camera/      # 위험도 조회
    ├── internal/    # Python AI 서버 수신 API (realtime·fall-log)
    ├── logs/        # 낙상 이력 CRUD·동영상 업로드(signed URL)·에스컬레이션/정합성 보정 스케줄러
    ├── notification/ # FCM 알림 발송 (서비스만 유지, 외부 컨트롤러 제거)
    ├── settings/    # 알림 설정·마케팅 수신 동의
    └── user/        # 유저 정보 관리 (verify-password, 탈퇴 시 cascade 삭제 포함)
```

---

## 환경 변수

`.env.example` 참조. 필수 항목:

| 변수 | 설명 |
|---|---|
| `JWT_SECRET` | JWT 서명 키 |
| `FIREBASE_STORAGE_BUCKET` | GCS 버킷명 (썸네일 Signed URL용) |
| `REDIS_HOST` | Redis 호스트 |

---

## 실행 방법

```bash
# Docker Compose (전체 스택)
docker-compose up --build

# Kotlin 서버만 (로컬)
./gradlew bootRun

# 테스트
./gradlew test
```

---

## API 문서

서버 실행 후: `http://localhost:8080/swagger-ui.html`

전체 명세: [`v4.0_onsafe_api_spec.md`](v4.0_onsafe_api_spec.md) (v4.2 적용)

---

## 문서

> ⚠️ `docs/`는 `.gitignore` 대상(로컬 전용, 저장소에 커밋되지 않음)입니다. 아래 표는 이 저장소를 새로 clone한 경우 존재하지 않을 수 있습니다 — 실제 존재 여부는 로컬 `docs/` 디렉터리를 직접 확인하세요.

| 문서 | 내용 |
|---|---|
| [`CHANGELOG.md`](CHANGELOG.md) | PR별 변경 이력 (git 추적됨) |
| [`v4.0_onsafe_api_spec.md`](v4.0_onsafe_api_spec.md) | v4.2 API 명세서 (최신, git 추적됨) |
| [`git-deploy-scope.md`](git-deploy-scope.md) | git 배포 파이프라인 범위 분류 (git 추적됨) |
| `docs/real-device-verification-guide.md` | 낙상 감지 mp4 파이프라인 실기기 검증 절차·설정값 (로컬 전용) |
| `docs/progress-report-2026-07-29.md` | mp4 파이프라인 진행상황 팀 공유용 요약 (로컬 전용, 일부 항목은 이후 병합 완료로 최신화 필요) |

---

## 배포 범위

> **분류 기준**: `git push → GitHub Actions → GCP Cloud Run` 자동 배포 파이프라인이 실제로 관여하는 항목인가.
> 전체 분류 근거는 [`git-deploy-scope.md`](git-deploy-scope.md) 참조.

### git 배포 파이프라인 (main push·PR 시 실행)

| 항목 | 역할 | 코드 위치 |
|---|---|---|
| **J9. GitHub Actions CI** | main push·PR 트리거, test(push·PR) → Docker 빌드 검증(PR만) | `.github/workflows/backend-ci.yml` |
| **J10. Workload Identity OIDC** | GitHub → GCP 인증 (키 없이) | `deploy-cloudrun.yml` auth step |
| **J1. Docker 컨테이너화 (Kotlin/Python)** | 배포용 이미지 빌드 | `Dockerfile.kotlin`, `Dockerfile.python` |
| **J8. Artifact Registry** | 빌드 이미지 push, Cloud Run이 pull | `deploy-cloudrun.yml` build/push |
| **J3. Cloud Run 배포** | Kotlin(public) / Python(internal) | `deploy-cloudrun.yml` deploy steps |
| **— Firestore 인덱스 적용** | `firestore.indexes.json`을 운영 Firestore에 반영 (실패해도 배포는 계속) | `deploy-cloudrun.yml` "Apply Firestore indexes" step |
| **— Cloud Scheduler 잡 등록** | `/internal/jobs/*` 트리거 3개 등록·갱신 (login-history-cleanup·heartbeat-watchdog·deletion-retry) | `deploy-cloudrun.yml` "Register Cloud Scheduler jobs" step |
| **J5. Secret Manager** | `--set-secrets`로 Cloud Run에 주입 | `deploy-cloudrun.yml` `--set-secrets` |
| **J6. Memorystore Redis** | `REDIS_HOST` env로 참조 | `deploy-cloudrun.yml` `--set-env-vars` |
| **J7. Firestore** | 앱 런타임 DB | Firebase Admin SDK |
| **J11. VPC 커넥터** | Cloud Run → Memorystore 내부망 경로 | `--vpc-connector onsafe-connector` |

### 배포 이후 운영 (파이프라인 외부, 별도 사이클)

| 항목 | 제외 이유 |
|---|---|
| **J4. Terraform IaC** | 인프라 프로비저닝은 별개 사이클. 수동 `terraform apply` — `infra/**`는 배포 워크플로의 `paths-ignore`로 트리거에서 제외됨 |
| **J12. 도메인/HTTPS/WSS 설정** | 최초 1회 세팅. Cloud Run 기본 `*.run.app` HTTPS 자동 제공 |

### 다른 문서로 분리

| 항목 | 소속 |
|---|---|
| **J1(Redis), J2. docker-compose 로컬 개발** | 개발자 로컬 환경 — `docker-compose.yml` (파이프라인 미사용) |
| **J13. Android AAB 서명 / Play 업로드** | OnSafe 프론트 저장소 |
| **J14. ProGuard / R8 최적화** | Android 앱 빌드 옵션 (백엔드 무관) |

---

## 관측 및 운영

> 이 섹션 전체가 **런타임/운영 영역**이라 매 배포마다 파이프라인이 건드리는 항목은 거의 없다.

### 앱 코드에 내장 (배포 산출물에 포함)

배포 파이프라인이 별도로 세팅하지 않고, 앱 코드에 이미 들어있어서 **자동으로 함께 배포된다**.

| 항목 | 실제 위치 |
|---|---|
| **K5. API Rate Limiter 적용** | `common/ratelimit/RateLimiter.kt` — 전역 필터가 아니라 일부 엔드포인트(로그인·회원가입·아이디/메일 확인·아이디 찾기·재설정 본인확인·페어링·비밀번호 확인)에서 서비스가 호출 |
| **K7. CORS 오리진 정책** | Python AI 서버 `app/main.py` `CORSMiddleware` (Kotlin API에는 CORS 설정 없음) |
| **K8. 표준 에러 응답** | `common/exception/*`, `ApiResponse` |

### 배포 이후 운영 (파이프라인 외부)

| 항목 | 제외 이유 |
|---|---|
| **K1. Cloud Logging 수집** | Cloud Run stdout/stderr 자동 수집. 파이프라인 개입 없음 |
| **K2. 구조화 JSON 로깅** | 미도입 — logback·`logging.structured` 설정 없이 기본 텍스트 로그. 도입하면 앱 코드에 포함되는 항목 |
| **K3. Cloud Monitoring 알림** | 알림 정책 미설정 — Terraform은 Monitoring API 활성화·`roles/monitoring.metricWriter`만 있음. 설정하게 되면 콘솔/Terraform 1회로 배포 주기와 무관 |
| **K4. 에러 집계 도구 (Sentry 등)** | 검토 단계 — 코드·파이프라인 모두 미도입 |
| **K6. Internal API IP 화이트리스트** | 미설정 — Python AI 서버만 배포 옵션 `--ingress internal-and-cloud-load-balancing`. Kotlin API는 공개 서비스라 `/internal/**`을 `X-Internal-Auth` 헤더 시크릿으로만 보호하며, IP 제한은 인프라 레벨 작업으로 남아 있음 |
