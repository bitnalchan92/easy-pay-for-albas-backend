package com.albapay.backend.data;

import com.albapay.backend.supabase.SupabaseClient;
import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

@RestController
@RequiredArgsConstructor
public class DataController {

    private final SupabaseClient supabaseClient;

    @GetMapping(value = "/data")
    public ResponseEntity<Map<String, Object>> getData() {
        CompletableFuture<List<Map<String, Object>>> workplaces = fetch("workplaces?select=*");
        CompletableFuture<List<Map<String, Object>>> workers = fetch("workers?select=*");
        CompletableFuture<List<Map<String, Object>>> worklogs = fetch("worklogs?select=*");
        CompletableFuture<List<Map<String, Object>>> payouts = fetch("payouts?select=*");

        try {
            CompletableFuture.allOf(workplaces, workers, worklogs, payouts).join();
        } catch (CompletionException e) {
            // unwrap so SupabaseException still reaches GlobalExceptionHandler's
            // BusinessException branch instead of the generic 500 fallback
            if (e.getCause() instanceof RuntimeException re) {
                throw re;
            }
            throw e;
        }

        Map<String, Object> responseBody = Map.of(
                "workplaces", workplaces.join(),
                "workers", workers.join(),
                "worklogs", worklogs.join(),
                "payouts", payouts.join()
        );
        return ResponseEntity.ok(responseBody);
    }

    private CompletableFuture<List<Map<String, Object>>> fetch(String path) {
        return CompletableFuture.supplyAsync(() -> {
            List<Map<String, Object>> result = supabaseClient.get(path, new ParameterizedTypeReference<>() {
            });
            return result != null ? result : List.<Map<String, Object>>of();
        });
    }
}
