package com.albapay.backend.worklog;

import com.albapay.backend.common.exception.BusinessException;
import com.albapay.backend.common.exception.ErrorCode;
import com.albapay.backend.supabase.SupabaseClient;
import com.albapay.backend.toss.TossSessionFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.LinkedHashMap;

@RestController
@RequiredArgsConstructor
public class WorklogController {

    private final SupabaseClient supabaseClient;

    @PostMapping(value = "/worklogs")
    public ResponseEntity<Map<String, Object>> create(
            @RequestAttribute(TossSessionFilter.ACTOR_USER_KEY) long actorUserKey,
            @RequestBody Map<String, Object> body) {
        Map<String, Object> worklog = new LinkedHashMap<>();
        copy(body, worklog, "worker_id", "date", "end_date", "start_time", "end_time", "memo");
        Map<String, Object> row = supabaseClient.post("rpc/create_active_worklog",
                Map.of("p_actor_user_key", actorUserKey, "p_body", worklog),
                new ParameterizedTypeReference<Map<String, Object>>() {},
                "return=representation");
        if (row == null) {
            throw new BusinessException(ErrorCode.WORKLOG_CREATE_FAILED);
        }
        requireAuthorized(row);
        row = new LinkedHashMap<>(row);
        row.remove("authorized");

        return ResponseEntity.ok(row);
    }

    @PatchMapping(value = "/worklogs")
    public ResponseEntity<Map<String, Object>> patch(
            @RequestAttribute(TossSessionFilter.ACTOR_USER_KEY) long actorUserKey,
            @RequestBody Map<String, Object> body) {
        Object id = body.get("id");
        Object patch = body.get("patch");
        if (id == null || patch == null) {
            throw new BusinessException(ErrorCode.MISSING_ID_OR_PATCH);
        }

        if (!(patch instanceof Map<?, ?> patchMap)) {
            throw new BusinessException(ErrorCode.MISSING_ID_OR_PATCH);
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("p_actor_user_key", actorUserKey);
        params.put("p_worklog_id", id);
        params.put("p_status", patchMap.get("status"));
        params.put("p_rejection_reason", patchMap.get("rejection_reason"));
        Map<String, Object> result = supabaseClient.post("rpc/transition_active_worklog", params,
                new ParameterizedTypeReference<Map<String, Object>>() {}, "return=representation");
        requireAuthorized(result);

        Map<String, Object> responseBody = Map.of("ok", true);
        return ResponseEntity.ok(responseBody);
    }

    private static void copy(Map<String, Object> source, Map<String, Object> target, String... keys) {
        for (String key : keys) if (source.containsKey(key)) target.put(key, source.get(key));
    }

    private static void requireAuthorized(Map<String, Object> result) {
        if (result == null || Boolean.FALSE.equals(result.get("authorized"))) {
            throw new BusinessException(ErrorCode.EMPLOYMENT_FORBIDDEN);
        }
    }
}
