from fastapi import HTTPException


def not_found(msg: str) -> HTTPException:
    return HTTPException(status_code=404, detail=msg)


def conflict(msg: str) -> HTTPException:
    return HTTPException(status_code=409, detail=msg)


def invalid_token() -> HTTPException:
    return HTTPException(status_code=401, detail="유효하지 않은 토큰입니다.")


# 메시지는 Kotlin ErrorCode.FORBIDDEN·REDIS_UNAVAILABLE과 맞춘다.
def forbidden() -> HTTPException:
    return HTTPException(status_code=403, detail="접근 권한이 없습니다.")


def service_unavailable() -> HTTPException:
    return HTTPException(status_code=503, detail="일시적으로 요청을 처리할 수 없습니다. 잠시 후 다시 시도해주세요.")
