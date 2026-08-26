from pydantic import BaseModel
from typing import Optional


class StreamResponse(BaseModel):
    score: float
    fall: bool
    level: Optional[str] = None
    log_id: Optional[str] = None


class ScoreResponse(BaseModel):
    user_id: str
    score: float
    level: str


class StatusResponse(BaseModel):
    device_id: str
    status: str
    last_seen: Optional[str] = None


class CameraUrlResponse(BaseModel):
    device_id: str
    camera_url: Optional[str]


