package com.albapay.backend.worker;

import com.albapay.backend.common.exception.BusinessException;
import com.albapay.backend.common.exception.ErrorCode;

import java.util.Map;
import java.util.Set;

public record DepartureRequest(String authorizationCode, String referrer, String confirmation) {
    private static final Set<String> FIELDS = Set.of("authorizationCode", "referrer", "confirmation");

    public static DepartureRequest from(Map<String, Object> body) {
        if (body == null || !FIELDS.containsAll(body.keySet())) {
            throw new BusinessException(ErrorCode.DEPARTURE_INVALID_REQUEST);
        }
        return new DepartureRequest(str(body.get("authorizationCode")), str(body.get("referrer")),
                str(body.get("confirmation")));
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
