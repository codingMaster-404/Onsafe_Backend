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
    # CORS: 쉼표로 구분된 허용 출처 목록. 미설정 시 Kotlin 서버만 허용
    cors_origins: str = ""

    model_config = {"env_file": ".env"}


settings = Settings()
