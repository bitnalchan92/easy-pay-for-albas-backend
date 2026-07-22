package com.albapay.backend.config;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * Restricts CORS to the configured frontend origin(s) — docs/api-spec.md explicitly forbids
 * the "*" wildcard the old Vercel functions used. Origins come from `albapay.cors.allowed-origins`
 * (comma-separated), already scaffolded in application.yml.
 */
@Configuration
@RequiredArgsConstructor
public class CorsConfig implements WebMvcConfigurer {

    private static final String[] APPS_IN_TOSS_ORIGINS = {
            "https://easyalbapay.apps.tossmini.com",
            "https://easyalbapay.private-apps.tossmini.com"
    };

    private final AlbapayProperties props;

    @Bean
    public FilterRegistrationBean<CorsFilter> corsFilter() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(List.of(allowedOrigins()));
        config.setAllowedMethods(List.of("GET", "POST", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Content-Type", "Authorization"));
        config.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);

        FilterRegistrationBean<CorsFilter> bean = new FilterRegistrationBean<>(new CorsFilter(source));
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return bean;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOrigins(allowedOrigins())
                .allowedMethods("GET", "POST", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("Content-Type", "Authorization")
                .allowCredentials(true);
    }

    private String[] allowedOrigins() {
        String[] origins = props.getCors().getAllowedOrigins().split(",");
        String[] allowedOrigins = new String[origins.length + APPS_IN_TOSS_ORIGINS.length];
        for (int i = 0; i < origins.length; i++) allowedOrigins[i] = origins[i].trim();
        System.arraycopy(APPS_IN_TOSS_ORIGINS, 0, allowedOrigins, origins.length,
                APPS_IN_TOSS_ORIGINS.length);
        return allowedOrigins;
    }
}
