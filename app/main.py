import logging
import logging.config

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from app.core.config import settings
from app.core.firebase import init_firebase
from app.domain.camera.router import router as camera_router, ws_router as camera_ws_router
from app.domain.devices.router import router as devices_router

logging.config.dictConfig({
    "version": 1,
    "disable_existing_loggers": False,
    "formatters": {
        "default": {
            "format": "%(asctime)s [%(levelname)s] %(name)s: %(message)s",
            "datefmt": "%Y-%m-%dT%H:%M:%S",
        },
    },
    "handlers": {
        "console": {
            "class": "logging.StreamHandler",
            "formatter": "default",
        },
    },
    "root": {
        "level": "INFO",
        "handlers": ["console"],
    },
})

app = FastAPI(
    title="OnSafe AI API",
    description="AI 카메라 기반 낙상 감지 및 실시간 모니터링 — AI 추론 전용 서버 v2.0",
    version="2.0.0",
)

# CORS_ORIGINS 환경변수 미설정 시 Kotlin 서버만 허용
_extra = [o.strip() for o in settings.cors_origins.split(",") if o.strip()]
_allowed_origins = list({settings.kotlin_internal_base, *_extra})

app.add_middleware(
    CORSMiddleware,
    allow_origins=_allowed_origins,
    allow_methods=["*"],
    allow_headers=["*"],
)


@app.on_event("startup")
async def startup() -> None:
    init_firebase()
    from app.ai.engine import _load_models
    _load_models()


@app.get("/health", tags=["Health"])
def health() -> dict:
    from app.ai.engine import _model, _scaler
    loaded = _model is not None and _scaler is not None
    return {
        "status": "ok" if loaded else "not_ready",
        "model_loaded": _model is not None,
        "scaler_loaded": _scaler is not None,
    }


app.include_router(camera_router)
app.include_router(camera_ws_router)
app.include_router(devices_router)
