package com.albapay.backend.worker;

import com.albapay.backend.common.exception.BusinessException;
import com.albapay.backend.common.exception.ErrorCode;
import com.albapay.backend.supabase.SupabaseClient;
import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class WorkerController {

    private final SupabaseClient supabaseClient;

    @PostMapping(value = "/workers")
    public ResponseEntity<Map<String, Object>> create(@RequestBody Map<String, Object> body) {
        List<Map<String, Object>> rows = supabaseClient.post(
                "workers",
                body,
                new ParameterizedTypeReference<>() {
                },
                "return=representation"
        );

        Map<String, Object> row = (rows == null || rows.isEmpty()) ? null : rows.get(0);
        if (row == null) {
            throw new BusinessException(ErrorCode.WORKER_CREATE_FAILED);
        }

        return ResponseEntity.status(HttpStatus.CREATED).body(row);
    }

    @PatchMapping(value = "/workers")
    public ResponseEntity<Map<String, Object>> patch(@RequestBody Map<String, Object> body) {
        Object id = body.get("id");
        Object patch = body.get("patch");
        if (id == null || patch == null) {
            throw new BusinessException(ErrorCode.MISSING_ID_OR_PATCH);
        }

        supabaseClient.patch(
                "workers?id=eq." + id,
                patch,
                new ParameterizedTypeReference<Void>() {
                },
                "return=minimal"
        );

        Map<String, Object> responseBody = Map.of("ok", true);
        return ResponseEntity.ok(responseBody);
    }
}
