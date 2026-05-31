package com.albapay.backend.supabase;

import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
@RequiredArgsConstructor
public class SupabaseClient {

    private final RestClient supabaseRestClient;

    public <T> T get(String path, ParameterizedTypeReference<T> type) {
        return supabaseRestClient.get()
                .uri(toUri(path))
                .retrieve()
                .body(type);
    }

    public <T> T post(String path, Object body, ParameterizedTypeReference<T> type, String prefer) {
        return supabaseRestClient.post()
                .uri(toUri(path))
                .header("Prefer", prefer)
                .body(body)
                .retrieve()
                .body(type);
    }

    public <T> T patch(String path, Object body, ParameterizedTypeReference<T> type, String prefer) {
        return supabaseRestClient.patch()
                .uri(toUri(path))
                .header("Prefer", prefer)
                .body(body)
                .retrieve()
                .body(type);
    }

    public void delete(String path) {
        supabaseRestClient.delete()
                .uri(toUri(path))
                .retrieve()
                .toBodilessEntity();
    }

    private String toUri(String path) {
        if ( path == null || path.isBlank() ) {
            throw new IllegalArgumentException("Supabase path must not be blank");
        }

        if (path.startsWith("/")) {
            throw new IllegalArgumentException("Supabase path must not start with '/'");
        }

        return "/" + path;
    }
}
