package com.albapay.backend.config;

import org.junit.jupiter.api.Test;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;

import java.lang.reflect.Method;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CorsConfigTest {
    @Test
    @SuppressWarnings("unchecked")
    void 허용된Origin에credentials를활성화한다() throws Exception {
        AlbapayProperties properties = new AlbapayProperties();
        properties.getCors().setAllowedOrigins("https://frontend.example.com");
        CorsRegistry registry = new CorsRegistry();
        new CorsConfig(properties).addCorsMappings(registry);

        Method method = CorsRegistry.class.getDeclaredMethod("getCorsConfigurations");
        method.setAccessible(true);
        Map<String, CorsConfiguration> mappings = (Map<String, CorsConfiguration>) method.invoke(registry);
        CorsConfiguration configuration = mappings.get("/**");

        assertThat(configuration.getAllowCredentials()).isTrue();
        assertThat(configuration.getAllowedOrigins()).contains(
                "https://frontend.example.com",
                "https://easyalbapay.apps.tossmini.com",
                "https://easyalbapay.private-apps.tossmini.com");
        assertThat(new CorsConfig(properties).corsFilter().getOrder()).isEqualTo(Integer.MIN_VALUE);
    }
}
