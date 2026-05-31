package com.albapay.backend.common.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "잘못된 요청입니다."),
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 오류가 발생했습니다."),
    SUPABASE_CONFLICT(HttpStatus.CONFLICT, "이미 존재하는 데이터입니다."),
    SUPABASE_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "데이터 처리에 실패했습니다.");

    private final HttpStatus status;
    private final String message;
}
