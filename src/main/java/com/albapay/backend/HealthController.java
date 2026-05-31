package com.albapay.backend;

import com.albapay.backend.supabase.SupabaseClient;
import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class HealthController {

    private final SupabaseClient supabaseClient;

    @GetMapping(value = "/health")
    public ResponseEntity<Map<String, String>> healthCheck() {
        List<Map<String, Object>> result = supabaseClient.get(
                "workplaces?select=*&limit=1",
                new ParameterizedTypeReference<>() {
                }
        );

        return ResponseEntity.ok(Map.of(
                "status", "ok",
                "supabase", result != null ? "connected" : "empty"
        ));
    }
}
