package com.albapay.backend.payout;

import com.albapay.backend.common.exception.BusinessException;
import com.albapay.backend.common.exception.ErrorCode;
import com.albapay.backend.supabase.SupabaseClient;
import com.albapay.backend.toss.TossClient;
import com.albapay.backend.toss.TossTokenResponse;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

@Service
public class PayoutService {
    private final TossClient tossClient;
    private final SupabaseClient supabaseClient;

    public PayoutService(@Lazy TossClient tossClient, SupabaseClient supabaseClient) {
        this.tossClient = tossClient;
        this.supabaseClient = supabaseClient;
    }

    public Map<String, Object> pay(PayoutRequest request) {
        if (request == null || blank(request.workerId()) || blank(request.period())
                || blank(request.authorizationCode()) || blank(request.referrer())
                || !"PAYMENT_SENT".equals(request.confirmation())) {
            throw new BusinessException(ErrorCode.PAYOUT_INVALID_REQUEST);
        }
        long actorUserKey;
        try {
            TossTokenResponse token = tossClient.generateToken(request.authorizationCode(), request.referrer());
            actorUserKey = tossClient.loginMe(token.accessToken()).userKey();
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.PAYOUT_AUTH_FAILED);
        }
        Map<String, Object> result = supabaseClient.post("rpc/pay_worker_period",
                Map.of("p_actor_user_key", actorUserKey, "p_worker_id", UUID.fromString(request.workerId()),
                        "p_period", request.period()),
                new ParameterizedTypeReference<Map<String, Object>>() {}, "return=representation");
        if (result == null || Boolean.FALSE.equals(result.get("found"))
                || Boolean.FALSE.equals(result.get("authorized"))) {
            throw new BusinessException(ErrorCode.PAYOUT_FORBIDDEN);
        }
        if (Boolean.FALSE.equals(result.get("allowed"))) {
            throw new BusinessException(ErrorCode.PAYOUT_NOT_ALLOWED);
        }
        return result;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
