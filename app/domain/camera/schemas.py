from pydantic import BaseModel
from typing import Optional


class StreamResponse(BaseModel):
    score: float
    fall: bool
    level: Optional[str] = None
    log_id: Optional[str] = None
