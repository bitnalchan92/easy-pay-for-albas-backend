package com.albapay.backend.payout;

import com.albapay.backend.common.exception.BusinessException;
import com.albapay.backend.common.exception.ErrorCode;
import com.albapay.backend.supabase.SupabaseClient;
import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class PayoutController {

    private final SupabaseClient supabaseClient;

    @PostMapping(value = "/payouts")
    public ResponseEntity<Map<String, Object>> create(@RequestBody Map<String, Object> body) {
        List<Map<String, Object>> rows = supabaseClient.post(
                "payouts",
                body,
                new ParameterizedTypeReference<>() {
                },
                "return=representation"
        );

        Map<String, Object> row = (rows == null || rows.isEmpty()) ? null : rows.get(0);
        if (row == null) {
            throw new BusinessException(ErrorCode.PAYOUT_CREATE_FAILED);
        }

        return ResponseEntity.status(HttpStatus.CREATED).body(row);
    }
}
