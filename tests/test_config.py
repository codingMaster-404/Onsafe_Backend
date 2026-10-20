from app.core.config import Settings


def test_env_file_with_kotlin_only_keys_still_loads(tmp_path):
    # 로컬 .env는 Kotlin과 같이 쓴다 — Python이 모르는 키가 있어도 기동해야 한다(D11)
    env = tmp_path / ".env"
    env.write_text(
        "JWT_SECRET=from-env-file\n"
        "ENCRYPTION_AES_KEY=kotlin-only\n"
        "SPRING_PROFILES_ACTIVE=docker\n",
        encoding="utf-8",
    )

    settings = Settings(_env_file=env)

    assert settings.jwt_secret == "from-env-file"
    assert not hasattr(settings, "encryption_aes_key")
