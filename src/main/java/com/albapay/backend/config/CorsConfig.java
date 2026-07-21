package com.albapay.backend.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Restricts CORS to the configured frontend origin(s) — docs/api-spec.md explicitly forbids
 * the "*" wildcard the old Vercel functions used. Origins come from `albapay.cors.allowed-origins`
 * (comma-separated), already scaffolded in application.yml.
 */
@Configuration
@RequiredArgsConstructor
public class CorsConfig implements WebMvcConfigurer {

    private final AlbapayProperties props;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        String[] origins = props.getCors().getAllowedOrigins().split(",");
        for (int i = 0; i < origins.length; i++) {
            origins[i] = origins[i].trim();
        }

        registry.addMapping("/**")
                .allowedOrigins(origins)
                .allowedMethods("GET", "POST", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("Content-Type", "Authorization");
    }
}
