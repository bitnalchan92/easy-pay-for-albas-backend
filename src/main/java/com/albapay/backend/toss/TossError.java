package com.albapay.backend.toss;

public record TossError(String errorCode, String reason) {

    public String messageOrDefault() {
        if (reason != null && !reason.isBlank()) return reason;
        if (errorCode != null && !errorCode.isBlank()) return errorCode;
        return "Toss API 요청에 실패했습니다.";
    }
}
