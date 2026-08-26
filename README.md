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
| 캐시·메시징 | Redis (블랙리스트, 인증코드 TTL) |
| 이메일 | AWS SES SDK (SesAsyncClient) |
| 스토리지 | Firebase Storage (GCS) — 낙상 동영상 MP4 클립 |
| 푸시 알림 | Firebase Cloud Messaging (FCM) |
| 인증 | JWT (JJWT 0.12.x) + Redis 블랙리스트 |
| 컨테이너 | Docker Compose (kotlin-api, python-ai, redis) |

---

## 주요 기능

- **낙상 감지 알림**: AI 추론 점수 기반 위험(75 초과)/주의(50 초과~75)/낙상 FCM 알림, 위험 6시간·주의 5분 쿨다운, 15분 sticky floor(위험 진입 시 점수 하락 방지), 2.5초 구간 스무딩
- **낙상 영상 클립**: 위험 등급 이벤트에 한해 4분 mp4 클립을 Android가 GCS에 직접 업로드(signed URL), 서버가 업로드 완료를 재확인 후 반영. 콜백 유실 시 정합성 보정 잡이 자동 복구
- **미확인 위험 이벤트 에스컬레이션**: 확인될 때까지 15분 주기로 재알림
- **이메일 인증**: 회원가입·비밀번호 재설정 6자리 코드 (AWS SES, 3분 TTL)
- **낙상 이력 관리**: 목록·단건 조회·확인·삭제, 동영상 Signed URL 발급
- **설정 관리**: 알림 토글(전체·소리·진동), 마케팅 수신 동의 on/off
- **개인정보 컴플라이언스**: 로그인 이력(감사 로그) 저장, 회원 탈퇴 시 개인정보 즉시 파기(cascade), 낙상 영상 URL AES-256-GCM 암호화

---

## 프로젝트 구조

자세한 파일별 설명은 [`docs/project-structure.md`](docs/project-structure.md) 참조

```
src/main/kotlin/com/onsafe/backend/
├── config/          # Firebase, Redis, Security, SES, Swagger
├── common/          # 예외처리, 응답 래퍼, JWT, Storage, Firestore 확장
└── domain/
    ├── auth/        # 로그인·회원가입·이메일인증·비밀번호재설정·로그인 이력
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
| `AWS_SES_REGION` | SES 리전 (예: `ap-northeast-2`) |
| `AWS_SES_FROM` | 발신자 이메일 |
| `AWS_ACCESS_KEY_ID` | AWS 자격증명 |
| `AWS_SECRET_ACCESS_KEY` | AWS 자격증명 |
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
| `docs/real-device-verification-guide.md` | 낙상 감지 mp4 파이프라인 실기기 검증 절차·설정값 (로컬 전용) |
| `docs/progress-report-2026-07-29.md` | mp4 파이프라인 진행상황 팀 공유용 요약 (로컬 전용, 일부 항목은 이후 병합 완료로 최신화 필요) |
