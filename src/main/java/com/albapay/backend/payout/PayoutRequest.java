package com.albapay.backend.payout;

import com.albapay.backend.common.exception.BusinessException;
import com.albapay.backend.common.exception.ErrorCode;

import java.util.Map;
import java.util.Set;

public record PayoutRequest(String workerId, String period, String authorizationCode, String referrer,
                            String confirmation) {
    private static final Set<String> FIELDS = Set.of("workerId", "period", "authorizationCode", "referrer", "confirmation");

    public static PayoutRequest from(Map<String, Object> body) {
        if (body == null || !FIELDS.containsAll(body.keySet())) {
            throw new BusinessException(ErrorCode.PAYOUT_INVALID_REQUEST);
        }
        return new PayoutRequest(str(body.get("workerId")), str(body.get("period")),
                str(body.get("authorizationCode")), str(body.get("referrer")), str(body.get("confirmation")));
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
