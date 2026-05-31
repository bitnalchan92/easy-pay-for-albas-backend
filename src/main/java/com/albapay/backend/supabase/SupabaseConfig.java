package com.albapay.backend.supabase;

import com.albapay.backend.config.AlbapayProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClient;

@Configuration
@RequiredArgsConstructor
public class SupabaseConfig {

    private final AlbapayProperties props;

    @Bean
    public RestClient supabaseRestClient() {
        String serviceRoleKey = props.getSupabase().getServiceRoleKey();

        return RestClient.builder()
                .baseUrl(props.getSupabase().getUrl() + "/rest/v1")
                .defaultHeader("apikey", serviceRoleKey)
                .defaultHeader("Authorization", "Bearer " + serviceRoleKey)
                .defaultHeader("Content-Type", "application/json")
                .defaultStatusHandler(
                        HttpStatusCode::isError,
                        (request, response) -> {
                            String body = new String(response.getBody().readAllBytes());
                            throw new SupabaseException(response.getStatusCode().value(), body);
                        }
                )
                .build();
    }
}
