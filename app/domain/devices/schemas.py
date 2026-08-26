from typing import Optional
from pydantic import BaseModel


class DeviceRegisterRequest(BaseModel):
    device_id: str
    device_name: str
    camera_url: Optional[str] = None
