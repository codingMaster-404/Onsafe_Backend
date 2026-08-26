package com.onsafe.backend.common.exception

/** 비즈니스 로직에서 발생하는 예외의 기본 클래스 */
class BusinessException(val errorCode: ErrorCode) : RuntimeException(errorCode.message)
