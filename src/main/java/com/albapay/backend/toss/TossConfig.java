package com.albapay.backend.toss;

import com.albapay.backend.config.AlbapayProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import javax.net.ssl.SSLContext;
import java.net.http.HttpClient;
import java.time.Duration;

@Configuration
@RequiredArgsConstructor
public class TossConfig {

    private final AlbapayProperties props;

    // @Lazy: parses the mTLS client cert (see TossMtlsSslContextFactory) — deferred so a
    // missing/invalid Toss cert only breaks Toss endpoints, not the whole app at startup.
    @Lazy
    @Bean
    public RestClient tossRestClient() {
        SSLContext sslContext = TossMtlsSslContextFactory.build(props.getToss());
        HttpClient httpClient = HttpClient.newBuilder()
                .sslContext(sslContext)
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        return RestClient.builder()
                .baseUrl(props.getToss().getApiBaseUrl())
                .requestFactory(new JdkClientHttpRequestFactory(httpClient))
                .defaultHeader("Content-Type", "application/json")
                .build();
    }
}
