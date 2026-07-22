package com.albapay.backend.worker;

import com.albapay.backend.common.exception.BusinessException;
import com.albapay.backend.common.exception.ErrorCode;
import com.albapay.backend.supabase.SupabaseClient;
import com.albapay.backend.toss.TossClient;
import com.albapay.backend.toss.TossTokenResponse;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class WorkerDepartureService {
    private final TossClient tossClient;
    private final SupabaseClient supabaseClient;

    public WorkerDepartureService(@Lazy TossClient tossClient, SupabaseClient supabaseClient) {
        this.tossClient = tossClient;
        this.supabaseClient = supabaseClient;
    }

    public String depart(String workerId, DepartureRequest request) {
        if (request == null || blank(request.authorizationCode()) || blank(request.referrer())
                || !"DEPART".equals(request.confirmation())) {
            throw new BusinessException(ErrorCode.DEPARTURE_INVALID_REQUEST);
        }
        long actorUserKey;
        try {
            TossTokenResponse token = tossClient.generateToken(request.authorizationCode(), request.referrer());
            actorUserKey = tossClient.loginMe(token.accessToken()).userKey();
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.DEPARTURE_AUTH_FAILED);
        }
        Map<String, Object> result = supabaseClient.post("rpc/depart_worker",
                Map.of("p_actor_user_key", actorUserKey, "p_worker_id", UUID.fromString(workerId)),
                new ParameterizedTypeReference<Map<String, Object>>() {}, "return=representation");
        if (result == null || Boolean.FALSE.equals(result.get("found"))) {
            throw new BusinessException(ErrorCode.DEPARTURE_NOT_FOUND);
        }
        if (Boolean.FALSE.equals(result.get("authorized"))) {
            throw new BusinessException(ErrorCode.DEPARTURE_FORBIDDEN);
        }
        if (Boolean.FALSE.equals(result.get("allowed"))) {
            Object raw = result.get("blockers");
            @SuppressWarnings("unchecked")
            List<String> blockers = raw instanceof List<?> ? (List<String>) raw : List.of();
            throw new WorkerDepartureBlockedException(blockers);
        }
        return String.valueOf(result.get("reason"));
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
