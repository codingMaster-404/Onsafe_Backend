from fastapi import APIRouter, Depends, status
from app.core.deps import get_current_user_id
from app.domain.devices import service
from app.domain.devices.schemas import DeviceRegisterRequest

router = APIRouter(prefix="/api/devices", tags=["Devices"])


@router.get("/{user_id}")
async def get_devices(
    user_id: str,
    _: str = Depends(get_current_user_id),
) -> dict:
    return await service.get_devices(user_id)


@router.post("/{user_id}", status_code=status.HTTP_201_CREATED)
async def register_device(
    user_id: str,
    req: DeviceRegisterRequest,
    _: str = Depends(get_current_user_id),
) -> dict:
    return await service.register_device(user_id, req)
