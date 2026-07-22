package com.albapay.backend.workplace;

import com.albapay.backend.common.exception.BusinessException;
import com.albapay.backend.common.exception.ErrorCode;
import com.albapay.backend.supabase.SupabaseClient;
import com.albapay.backend.toss.TossSessionFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class WorkplaceController {

    private final SupabaseClient supabaseClient;

    @PostMapping(value = "/workplaces")
    public ResponseEntity<Map<String, Object>> create(
            @RequestAttribute(TossSessionFilter.ACTOR_USER_KEY) long actorUserKey,
            @RequestBody Map<String, Object> body) {
        if (body.containsKey("userKey") || body.containsKey("owner_toss_user_key")
                || body.containsKey("owner_device_id")) {
            throw new BusinessException(ErrorCode.EMPLOYMENT_INVALID_REQUEST);
        }
        Map<String, Object> workplace = new LinkedHashMap<>();
        copy(body, workplace, "name", "address", "address_detail", "invite_code",
                "hourly_wage_default", "payday", "business_number");
        Map<String, Object> row = supabaseClient.post(
                "rpc/create_workplace_for_actor",
                Map.of("p_actor_user_key", actorUserKey, "p_body", workplace),
                new ParameterizedTypeReference<Map<String, Object>>() {},
                "return=representation"
        );
        if (row == null) {
            throw new BusinessException(ErrorCode.WORKPLACE_CREATE_FAILED);
        }

        return ResponseEntity.status(HttpStatus.CREATED).body(row);
    }

    private static void copy(Map<String, Object> source, Map<String, Object> target, String... keys) {
        for (String key : keys) if (source.containsKey(key)) target.put(key, source.get(key));
    }
}
