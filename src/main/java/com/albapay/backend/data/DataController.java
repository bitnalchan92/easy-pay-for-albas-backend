package com.albapay.backend.data;

import com.albapay.backend.supabase.SupabaseClient;
import com.albapay.backend.toss.TossSessionFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequiredArgsConstructor
public class DataController {

    private final SupabaseClient supabaseClient;

    @GetMapping(value = "/data")
    public ResponseEntity<Map<String, Object>> getData(
            @RequestAttribute(TossSessionFilter.ACTOR_USER_KEY) long actorUserKey) {
        Map<String, Object> result = supabaseClient.post("rpc/get_actor_data",
                Map.of("p_actor_user_key", actorUserKey),
                new ParameterizedTypeReference<Map<String, Object>>() {}, "return=representation");
        return ResponseEntity.ok(result == null ? Map.of(
                "workplaces", java.util.List.of(), "workers", java.util.List.of(),
                "worklogs", java.util.List.of(), "payouts", java.util.List.of()) : result);
    }
}
