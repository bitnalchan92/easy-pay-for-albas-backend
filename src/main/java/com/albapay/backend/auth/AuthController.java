package com.albapay.backend.auth;

import com.albapay.backend.common.exception.BusinessException;
import com.albapay.backend.common.exception.ErrorCode;
import com.albapay.backend.supabase.SupabaseClient;
import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
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
        String businessNumber = stringOrNull(body.get("businessNumber"));
        String passwordHash = stringOrNull(body.get("passwordHash"));
        if (isBlank(businessNumber) || isBlank(passwordHash)) {
            throw new BusinessException(ErrorCode.OWNER_LOGIN_MISSING_FIELDS);
        }

        List<Map<String, Object>> rows = supabaseClient.get(
                "workplaces?select=id&business_number=eq." + encode(businessNumber)
                        + "&password_hash=eq." + encode(passwordHash) + "&limit=1",
                new ParameterizedTypeReference<>() {
                }
        );
        Map<String, Object> row = firstOrNull(rows);
        if (row == null) {
            throw new BusinessException(ErrorCode.OWNER_LOGIN_FAILED);
        }

        Map<String, Object> responseBody = new HashMap<>();
        responseBody.put("workplaceId", row.get("id"));
        return ResponseEntity.ok(responseBody);
    }

    @PostMapping("/worker-join")
    public ResponseEntity<Map<String, Object>> workerJoin(@RequestBody Map<String, Object> body) {
        String inviteCode = stringOrNull(body.get("inviteCode"));
        String name = stringOrNull(body.get("name"));
        String authMethod = stringOrNull(body.get("authMethod"));
        String deviceId = stringOrNull(body.get("deviceId"));
        Object tossUserKey = body.get("tossUserKey");

        if (isBlank(inviteCode) || isBlank(name) || isBlank(authMethod)) {
            throw new BusinessException(ErrorCode.WORKER_JOIN_MISSING_PARAMS);
        }

        List<Map<String, Object>> wpRows = supabaseClient.get(
                "workplaces?invite_code=eq." + encode(inviteCode.toUpperCase()) + "&limit=1",
                new ParameterizedTypeReference<>() {
                }
        );
        Map<String, Object> wpRow = firstOrNull(wpRows);
        if (wpRow == null) {
            throw new BusinessException(ErrorCode.WORKER_JOIN_INVITE_CODE_NOT_FOUND);
        }

        if ("toss".equals(authMethod)) {
            return joinWithToss(tossUserKey, name, wpRow);
        }
        if ("device".equals(authMethod)) {
            return joinWithDevice(deviceId, name, wpRow);
        }
        throw new BusinessException(ErrorCode.WORKER_JOIN_UNSUPPORTED_AUTH_METHOD);
    }

    private ResponseEntity<Map<String, Object>> joinWithToss(Object tossUserKey, String name, Map<String, Object> wpRow) {
        if (tossUserKey == null || isBlank(stringOrNull(tossUserKey))) {
            throw new BusinessException(ErrorCode.WORKER_JOIN_MISSING_TOSS_USER_KEY);
        }

        Object workplaceId = wpRow.get("id");
        // NOTE: tossUserKey is intentionally not URL-encoded here, matching the TS source
        // (`toss_user_key=eq.${tossUserKey}`), unlike deviceId/name below which are encoded there.
        List<Map<String, Object>> existingRows = supabaseClient.get(
                "workers?workplace_id=eq." + workplaceId + "&toss_user_key=eq." + stringOrNull(tossUserKey) + "&limit=1",
                new ParameterizedTypeReference<>() {
                }
        );
        Map<String, Object> existingRow = firstOrNull(existingRows);
        if (existingRow != null) {
            return ResponseEntity.ok(Map.of("worker", existingRow, "workplace", wpRow));
        }

        Map<String, Object> newWorker = new HashMap<>();
        newWorker.put("workplace_id", workplaceId);
        // keep the original JSON number type (not the stringified form) so Supabase's
        // bigint column receives a numeric value, matching the TS source's untouched `tossUserKey`.
        newWorker.put("toss_user_key", tossUserKey);
        newWorker.put("device_id", null);
        newWorker.put("name", name.trim());
        newWorker.put("phone", "");
        newWorker.put("hourly_wage", wpRow.get("hourly_wage_default"));
        newWorker.put("payday", wpRow.get("payday"));
        newWorker.put("status", "pending");

        Map<String, Object> newRow = createWorker(newWorker);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("worker", newRow, "workplace", wpRow));
    }

    private ResponseEntity<Map<String, Object>> joinWithDevice(String deviceId, String name, Map<String, Object> wpRow) {
        if (isBlank(deviceId)) {
            throw new BusinessException(ErrorCode.WORKER_JOIN_MISSING_DEVICE_ID);
        }
        String trimmedName = name.trim();
        Object workplaceId = wpRow.get("id");

        List<Map<String, Object>> existingRows = supabaseClient.get(
                "workers?workplace_id=eq." + workplaceId + "&device_id=eq." + encode(deviceId) + "&limit=1",
                new ParameterizedTypeReference<>() {
                }
        );
        Map<String, Object> existingRow = firstOrNull(existingRows);
        if (existingRow != null) {
            return ResponseEntity.ok(Map.of("worker", existingRow, "workplace", wpRow));
        }

        // 이름이 같고 미연결된(device_id IS NULL) worker가 있으면 클레임(연결)
        List<Map<String, Object>> unclaimedRows = supabaseClient.get(
                "workers?workplace_id=eq." + workplaceId + "&name=eq." + encode(trimmedName) + "&device_id=is.null&limit=1",
                new ParameterizedTypeReference<>() {
                }
        );
        Map<String, Object> unclaimedRow = firstOrNull(unclaimedRows);
        if (unclaimedRow != null) {
            List<Map<String, Object>> claimedRows = supabaseClient.patch(
                    "workers?id=eq." + unclaimedRow.get("id"),
                    Map.of("device_id", deviceId),
                    new ParameterizedTypeReference<List<Map<String, Object>>>() {
                    },
                    "return=representation"
            );
            Map<String, Object> claimedRow = firstOrNull(claimedRows);
            if (claimedRow == null) {
                throw new BusinessException(ErrorCode.WORKER_CLAIM_FAILED);
            }
            return ResponseEntity.ok(Map.of("worker", claimedRow, "workplace", wpRow));
        }

        Map<String, Object> newWorker = new HashMap<>();
        newWorker.put("workplace_id", workplaceId);
        newWorker.put("device_id", deviceId);
        newWorker.put("name", trimmedName);
        newWorker.put("phone", "");
        newWorker.put("hourly_wage", wpRow.get("hourly_wage_default"));
        newWorker.put("payday", wpRow.get("payday"));
        newWorker.put("status", "pending");

        Map<String, Object> newRow = createWorker(newWorker);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("worker", newRow, "workplace", wpRow));
    }

    private Map<String, Object> createWorker(Map<String, Object> newWorker) {
        List<Map<String, Object>> newRows = supabaseClient.post(
                "workers",
                newWorker,
                new ParameterizedTypeReference<>() {
                },
                "return=representation"
        );
        Map<String, Object> newRow = firstOrNull(newRows);
        if (newRow == null) {
            throw new BusinessException(ErrorCode.WORKER_CREATE_FAILED);
        }
        return newRow;
    }

    private static Map<String, Object> firstOrNull(List<Map<String, Object>> rows) {
        return (rows == null || rows.isEmpty()) ? null : rows.get(0);
    }

    private static String stringOrNull(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
