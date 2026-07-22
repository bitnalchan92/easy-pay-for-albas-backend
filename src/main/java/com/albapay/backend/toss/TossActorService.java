package com.albapay.backend.toss;

import com.albapay.backend.common.exception.BusinessException;
import com.albapay.backend.common.exception.ErrorCode;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class TossActorService {
    private final TossClient tossClient;

    public TossActorService(@Lazy TossClient tossClient) {
        this.tossClient = tossClient;
    }

    public long authenticate(Map<String, Object> body) {
        if (body == null || body.containsKey("userKey") || body.containsKey("tossUserKey")
                || body.containsKey("user_key")) {
            throw new BusinessException(ErrorCode.EMPLOYMENT_INVALID_REQUEST);
        }
        String authorizationCode = string(body.get("authorizationCode"));
        String referrer = string(body.get("referrer"));
        if (authorizationCode == null || authorizationCode.isBlank() || referrer == null || referrer.isBlank()) {
            throw new BusinessException(ErrorCode.EMPLOYMENT_AUTH_FAILED);
        }
        try {
            TossTokenResponse token = tossClient.generateToken(authorizationCode, referrer);
            return tossClient.loginMe(token.accessToken()).userKey();
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.EMPLOYMENT_AUTH_FAILED);
        }
    }

    private static String string(Object value) {
        return value instanceof String text ? text : null;
    }
}
