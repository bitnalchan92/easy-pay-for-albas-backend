package com.albapay.backend.auth;

import com.albapay.backend.common.exception.BusinessException;
import com.albapay.backend.common.exception.ErrorCode;
import com.albapay.backend.supabase.SupabaseClient;
import com.albapay.backend.toss.TossSessionFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Ports api/auth/owner-login.ts and api/auth/worker-join.ts as a faithful lift-and-shift.
 * Wrong-method handling is covered globally (GlobalExceptionHandler + @PostMapping), so unlike the
 * TS source there is no manual 405 check here.
 */
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final SupabaseClient supabaseClient;

    @PostMapping("/owner-login")
    public ResponseEntity<Map<String, Object>> ownerLogin(@RequestBody Map<String, Object> body) {
        throw new BusinessException(ErrorCode.LEGACY_AUTH_DISABLED);
    }

    @PostMapping("/worker-join")
    public ResponseEntity<Map<String, Object>> workerJoin(
            @RequestAttribute(TossSessionFilter.ACTOR_USER_KEY) long actorUserKey,
            @RequestBody Map<String, Object> body) {
        String inviteCode = stringOrNull(body.get("inviteCode"));
        String name = stringOrNull(body.get("name"));
        String authMethod = stringOrNull(body.get("authMethod"));
        if (isBlank(inviteCode) || isBlank(name) || isBlank(authMethod)) {
            throw new BusinessException(ErrorCode.WORKER_JOIN_MISSING_PARAMS);
        }
        if (!"toss".equals(authMethod)) {
            throw new BusinessException(ErrorCode.LEGACY_AUTH_DISABLED);
        }

        Map<String, Object> result = supabaseClient.post("rpc/join_worker_by_toss", Map.of(
                        "p_actor_user_key", actorUserKey,
                        "p_invite_code", inviteCode.toUpperCase(),
                        "p_name", name.trim()),
                new ParameterizedTypeReference<Map<String, Object>>() {}, "return=representation");
        if (result == null || Boolean.FALSE.equals(result.get("found"))) {
            throw new BusinessException(ErrorCode.WORKER_JOIN_INVITE_CODE_NOT_FOUND);
        }
        return ResponseEntity.ok(Map.of("worker", result.get("worker"), "workplace", result.get("workplace")));
    }

    private static String stringOrNull(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

}
