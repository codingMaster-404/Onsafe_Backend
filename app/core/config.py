from pydantic_settings import BaseSettings


class Settings(BaseSettings):
    # 빈 값 = ADC (Application Default Credentials) 사용 — Cloud Run 기본 동작
    # 로컬/docker-compose에서는 파일 경로를 명시적으로 주입 (예: /app/secrets/serviceAccountKey.json)
    firebase_credentials: str = ""
    firebase_storage_bucket: str = ""  # e.g. "your-project.appspot.com"
    redis_url: str = "redis://localhost:6379"
    jwt_secret: str = "change-me-to-a-32-char-secret-key"
    jwt_access_expiry: int = 3600
    jwt_refresh_expiry: int = 604800
    kotlin_internal_base: str = "http://localhost:8080"
    # Kotlin /internal/* 호출 시 X-Internal-Auth 헤더로 보낼 시크릿.
    # Secret Manager 슬롯 INTERNAL_JOB_SECRET 과 같은 값이어야 한다 — 비어 있으면 Kotlin이 403.
    internal_job_secret: str = ""
    # CORS: 쉼표로 구분된 허용 출처 목록. 미설정 시 Kotlin 서버만 허용
    cors_origins: str = ""

    # 로컬 .env는 Kotlin과 같이 쓰므로(ENCRYPTION_AES_KEY 등) 모르는 키는 무시한다 — 거부하면 기동하지 못한다.
    # 변수 이름 오타도 조용히 무시되니 새 설정을 넣을 때는 이름을 확인할 것.
    model_config = {"env_file": ".env", "extra": "ignore"}


settings = Settings()
