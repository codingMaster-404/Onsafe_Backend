from fastapi import APIRouter, Depends, status
from app.core.deps import get_current_user_id, require_same_user
from app.domain.devices import service
from app.domain.devices.schemas import DeviceRegisterRequest

router = APIRouter(prefix="/api/devices", tags=["Devices"])


@router.get("/{user_id}")
async def get_devices(
    user_id: str,
    current_user_id: str = Depends(get_current_user_id),
) -> dict:
    require_same_user(user_id, current_user_id)
    return await service.get_devices(user_id)


@router.post("/{user_id}", status_code=status.HTTP_201_CREATED)
async def register_device(
    user_id: str,
    req: DeviceRegisterRequest,
    current_user_id: str = Depends(get_current_user_id),
) -> dict:
    # 남의 userId로 기기를 재귀속하지 못하게 한다. 같은 사용자 안의 upsert(재로그인·기기 이전)는 그대로 둔다.
    require_same_user(user_id, current_user_id)
    return await service.register_device(user_id, req)
