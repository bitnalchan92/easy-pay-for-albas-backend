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
    SUPABASE_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "데이터 처리에 실패했습니다."),
    MISSING_ID_OR_PATCH(HttpStatus.BAD_REQUEST, "id와 patch가 필요합니다."),
    WORKER_CREATE_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "직원 등록에 실패했습니다."),
    WORKLOG_CREATE_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "근무일지 등록에 실패했습니다."),
    WORKPLACE_CREATE_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "사업장 생성에 실패했습니다."),
    PAYOUT_CREATE_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "지급 처리에 실패했습니다."),
    INVALID_BIZ_NUMBER(HttpStatus.BAD_REQUEST, "사업자번호 형식이 올바르지 않습니다."),
    BIZ_NOT_FOUND(HttpStatus.NOT_FOUND, "조회 결과가 없습니다."),
    NTS_API_ERROR(HttpStatus.BAD_GATEWAY, "국세청 API 호출에 실패했습니다."),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "허용되지 않은 요청입니다."),
    TOSS_MISSING_AUTH_CODE(HttpStatus.BAD_REQUEST, "authorizationCode와 referrer가 필요합니다."),
    TOSS_MISSING_USER_KEY(HttpStatus.BAD_REQUEST, "userKey가 필요합니다."),
    TOSS_MISSING_CALLBACK_FIELDS(HttpStatus.BAD_REQUEST, "userKey와 referrer가 필요합니다."),
    TOSS_CALLBACK_UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "인증에 실패했습니다."),
    TOSS_REFRESH_TOKEN_NOT_FOUND(HttpStatus.NOT_FOUND, "저장된 refreshToken이 없습니다."),
    TOSS_ACCESS_TOKEN_NOT_FOUND(HttpStatus.NOT_FOUND, "저장된 accessToken이 없습니다."),
    TOSS_EXCHANGE_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "토스 로그인 서버 처리에 실패했습니다."),
    TOSS_REFRESH_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "토큰 재발급에 실패했습니다."),
    TOSS_DISCONNECT_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "토스 로그인 연결 해제에 실패했습니다."),
    TOSS_CALLBACK_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "토스 로그인 콜백 처리에 실패했습니다."),
    OWNER_LOGIN_MISSING_FIELDS(HttpStatus.BAD_REQUEST, "사업자번호와 비밀번호가 필요합니다."),
    OWNER_LOGIN_FAILED(HttpStatus.UNAUTHORIZED, "사업자번호 또는 비밀번호가 올바르지 않습니다."),
    WORKER_JOIN_MISSING_PARAMS(HttpStatus.BAD_REQUEST, "필수 파라미터가 누락되었습니다."),
    WORKER_JOIN_INVITE_CODE_NOT_FOUND(HttpStatus.NOT_FOUND, "초대 코드를 찾을 수 없습니다."),
    WORKER_JOIN_MISSING_TOSS_USER_KEY(HttpStatus.BAD_REQUEST, "tossUserKey가 필요합니다."),
    WORKER_JOIN_MISSING_DEVICE_ID(HttpStatus.BAD_REQUEST, "deviceId가 필요합니다."),
    WORKER_JOIN_UNSUPPORTED_AUTH_METHOD(HttpStatus.BAD_REQUEST, "지원하지 않는 인증 방식입니다."),
    WORKER_CLAIM_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "직원 연결에 실패했습니다.");

    private final HttpStatus status;
    private final String message;
}
