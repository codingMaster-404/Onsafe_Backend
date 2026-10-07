# OnSafe git 배포 범위 정리

> **분류 기준**: `git push → GitHub Actions → GCP Cloud Run` 자동 배포 파이프라인이 **실제로 관여하는 항목**인가

---

## 한눈에 보기

| 섹션 | 필요 | 불필요 |
|---|---|---|
| **10. 인프라 및 배포** | J1(Kotlin·Python), J3, J5, J6, J7, J8, J9, J10, J11 | J1(Redis), J2, J4, J12, J13, J14 |
| **11. 관측 및 운영** | 없음 (K5·K7·K8은 앱 코드에 포함돼 함께 배포) | K1, K2(미도입), K3, K4, K6 |

---

# 10. 인프라 및 배포

## ✅ git 배포에 필요

### 파이프라인 실행 주체·산출물
| 항목 | 역할 |
|---|---|
| **J9. GitHub Actions CI (test + build)** | 파이프라인 실행 주체. main push·PR 트리거 — test는 둘 다, Docker 빌드 검증은 PR만 (main push 빌드는 배포 워크플로가 수행) |
| **J10. Workload Identity OIDC** | GitHub Actions → GCP 인증 (키 없이) |
| **J1. Docker 컨테이너화 (Kotlin / Python)** | 배포용 이미지 빌드 |
| **J8. Artifact Registry** | 빌드된 이미지 push, Cloud Run이 pull |
| **J3. Cloud Run 배포 (Kotlin 퍼블릭 / Python 내부)** | 최종 배포 타깃 |
| **— Firestore 인덱스 적용** | `firestore.indexes.json`을 운영 Firestore에 반영 (실패해도 배포는 계속) |
| **— Cloud Scheduler 잡 등록** | `/internal/jobs/*` 트리거 3개 등록·갱신 (login-history-cleanup·heartbeat-watchdog·deletion-retry) |

### 앱 런타임 전제조건 (배포 파이프라인이 참조·주입)
| 항목 | 역할 |
|---|---|
| **J5. Secret Manager** | `--set-secrets`로 Cloud Run에 주입 (JWT_SECRET, ENCRYPTION_AES_KEY 등) |
| **J6. Memorystore Redis 1GB** | `REDIS_HOST` env로 참조 |
| **J7. Firestore Datastore** | 앱 런타임 DB (Firebase Admin SDK로 접근) |
| **J11. VPC 커넥터** | Cloud Run → Memorystore 내부망 접근 경로 |

## ❌ git 배포와 무관

| 항목 | 제외 이유 |
|---|---|
| **J1(Redis 부분). Redis 컨테이너화** | 프로덕션은 Memorystore 매니지드 사용. 컨테이너 이미지는 로컬 compose 전용 |
| **J2. docker-compose 로컬 개발** | 개발자 로컬 환경 도구. 배포 파이프라인 미사용 |
| **J4. Terraform IaC** | 인프라 프로비저닝은 **별개 사이클**. 앱 배포와 다른 주기로 수동 `apply` |
| **J12. 도메인 / HTTPS / WSS 설정** | 최초 1회 세팅. Cloud Run 기본 `*.run.app` HTTPS 자동 제공, 매 배포마다 안 건드림 |
| **J13. Android AAB 서명 / Play 업로드** | 다른 저장소 (프론트) + 다른 배포 파이프라인 (Play Console) |
| **J14. ProGuard / R8 최적화** | Android 앱 빌드 옵션. 백엔드 배포와 무관 |

---

# 11. 관측 및 운영

> 이 섹션 전체가 **런타임/운영 영역**이라 매 배포마다 파이프라인이 건드리는 항목은 거의 없음.

## ✅ 배포 산출물(앱 코드)에 이미 포함

배포 파이프라인이 별도로 세팅하지 않고, 앱 코드에 이미 들어있어서 **자동으로 함께 배포됨**.

| 항목 | 실제 위치 |
|---|---|
| **K5. API Rate Limiter 적용** | `common/ratelimit/RateLimiter.kt` — 전역 필터가 아니라 일부 엔드포인트(로그인·회원가입·아이디/메일 확인·아이디 찾기·재설정 본인확인·페어링·비밀번호 확인)에서 서비스가 호출 |
| **K7. CORS 오리진 정책** | Python AI 서버 `app/main.py` `CORSMiddleware` (Kotlin API에는 CORS 설정 없음) |
| **K8. 표준 에러 응답** | `common/exception/*`, `ApiResponse` |

## ❌ git 배포와 무관

| 항목 | 제외 이유 |
|---|---|
| **K1. Cloud Logging 수집** | Cloud Run stdout/stderr 자동 수집. 파이프라인 개입 없음 |
| **K2. 구조화 JSON 로깅** | 미도입 — logback·`logging.structured` 설정 없이 기본 텍스트 로그. 도입하면 앱 코드에 포함되는 항목 |
| **K3. Cloud Monitoring 알림** | 알림 정책 미설정 — Terraform은 Monitoring API 활성화·`roles/monitoring.metricWriter`만 있음. 설정하게 되면 콘솔/Terraform 1회로 배포 주기와 무관 |
| **K4. 에러 집계 도구 (Sentry 등)** | "검토" 단계 — 코드·파이프라인 모두 미도입 |
| **K6. Internal API IP 화이트리스트** | 미설정 — Python AI 서버만 배포 옵션 `--ingress internal-and-cloud-load-balancing`. Kotlin API는 공개 서비스라 `/internal/**`을 `X-Internal-Auth` 헤더 시크릿으로만 보호하며, IP 제한은 인프라 레벨 작업으로 남아 있음 |

---

# 결론

## 배포 파이프라인 슬라이드에 남겨야 할 항목
`J1(Kotlin·Python), J3, J5, J6, J7, J8, J9, J10, J11`

## "배포 이후 운영" 섹션으로 분리해야 할 항목
`J4, J12, K1, K2, K3, K4, K5, K6, K7, K8`

## 다른 문서(로컬 개발 가이드, Android 배포)로 이동해야 할 항목
`J1(Redis), J2, J13, J14`
