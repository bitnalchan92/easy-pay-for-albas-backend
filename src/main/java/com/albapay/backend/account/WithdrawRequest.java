package com.albapay.backend.account;

import com.albapay.backend.common.exception.BusinessException;
import com.albapay.backend.common.exception.ErrorCode;

import java.util.Map;
import java.util.Set;

/**
 * POST /account/withdraw의 유일하게 허용되는 body. {@link #from(Map)}이 딱 이 3개 필드만 허용하고
 * 그 외 키(특히 caller가 넣은 {@code userKey})는 400으로 거부한다 — 남의 계정을 지정할 수 없게 한다.
 * 탈퇴 대상은 오직 Toss login-me에서 확정하며 body의 어떤 식별자도 신뢰하지 않는다.
 *
 * Jackson 전역 {@code FAIL_ON_UNKNOWN_PROPERTIES}에 의존하지 않는다(끄면 무시되고, 켜면 Toss/Supabase
 * 응답 record 역직렬화까지 깨진다). 그래서 거부를 여기 코드로 강제한다.
 */
public record WithdrawRequest(
        String authorizationCode,
        String referrer,
        String confirmation
) {
    private static final Set<String> ALLOWED_FIELDS = Set.of("authorizationCode", "referrer", "confirmation");

    public static WithdrawRequest from(Map<String, Object> body) {
        if (body == null) {
            throw new BusinessException(ErrorCode.WITHDRAW_INVALID_REQUEST);
        }
        for (String key : body.keySet()) {
            if (!ALLOWED_FIELDS.contains(key)) {
                throw new BusinessException(ErrorCode.WITHDRAW_INVALID_REQUEST);
            }
        }
        return new WithdrawRequest(
                str(body.get("authorizationCode")),
                str(body.get("referrer")),
                str(body.get("confirmation")));
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }
}
