package com.albapay.backend.worker;

import com.albapay.backend.common.exception.BusinessException;
import com.albapay.backend.common.exception.ErrorCode;
import com.albapay.backend.supabase.SupabaseClient;
import com.albapay.backend.toss.TossSessionFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequiredArgsConstructor
public class WorkerController {

    private final SupabaseClient supabaseClient;

    @PatchMapping(value = "/workers")
    public ResponseEntity<Map<String, Object>> patch(
            @RequestAttribute(TossSessionFilter.ACTOR_USER_KEY) long actorUserKey,
            @RequestBody Map<String, Object> body) {
        Object id = body.get("id");
        Object patch = body.get("patch");
        if (id == null || patch == null) {
            throw new BusinessException(ErrorCode.MISSING_ID_OR_PATCH);
        }

        Map<String, Object> result = supabaseClient.post("rpc/patch_active_worker",
                Map.of("p_actor_user_key", actorUserKey, "p_worker_id", id, "p_patch", patch),
                new ParameterizedTypeReference<Map<String, Object>>() {}, "return=representation");
        requireAuthorized(result);

        Map<String, Object> responseBody = Map.of("ok", true);
        return ResponseEntity.ok(responseBody);
    }

    private static void requireAuthorized(Map<String, Object> result) {
        if (result == null || Boolean.FALSE.equals(result.get("authorized"))) {
            throw new BusinessException(ErrorCode.EMPLOYMENT_FORBIDDEN);
        }
    }
}
