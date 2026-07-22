package com.albapay.backend.common.exception;

import com.albapay.backend.account.WithdrawalBlockedException;
import com.albapay.backend.supabase.SupabaseException;
import com.albapay.backend.worker.WorkerDepartureBlockedException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 409 WITHDRAWAL_BLOCKED만 blockers를 담아 확장한다. 나머지 BusinessException은 {code,message}로 처리. */
    @ExceptionHandler(WithdrawalBlockedException.class)
    public ResponseEntity<Map<String, Object>> handleWithdrawalBlocked(WithdrawalBlockedException e) {
        ErrorCode errorCode = e.getErrorCode();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", errorCode.name());
        body.put("message", errorCode.getMessage());
        body.put("blockers", e.getBlockers());
        return ResponseEntity.status(errorCode.getStatus()).body(body);
    }

    @ExceptionHandler(WorkerDepartureBlockedException.class)
    public ResponseEntity<Map<String, Object>> handleDepartureBlocked(WorkerDepartureBlockedException e) {
        ErrorCode errorCode = e.getErrorCode();
        return ResponseEntity.status(errorCode.getStatus()).body(Map.of(
                "code", errorCode.name(), "message", errorCode.getMessage(), "blockers", e.getBlockers()));
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusinessException(BusinessException e) {
        ErrorCode errorCode = e.getErrorCode();

        if ( e instanceof SupabaseException se ) {
            log.error("Supabase error: {}", se.getDetail());
        }

        return ResponseEntity
                .status(errorCode.getStatus())
                .body(ErrorResponse.of(errorCode));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        ErrorCode errorCode = ErrorCode.METHOD_NOT_ALLOWED;

        return ResponseEntity
                .status(errorCode.getStatus())
                .body(ErrorResponse.of(errorCode));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgumentException(IllegalArgumentException e) {
        ErrorCode errorCode = ErrorCode.INVALID_REQUEST;

        return ResponseEntity
                .status(errorCode.getStatus())
                .body(ErrorResponse.of(errorCode));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleException(Exception e) {
        log.error("Unexpected server error", e);

        ErrorCode errorCode = ErrorCode.INTERNAL_SERVER_ERROR;

        return ResponseEntity
                .status(errorCode.getStatus())
                .body(ErrorResponse.of(errorCode));
    }
}
