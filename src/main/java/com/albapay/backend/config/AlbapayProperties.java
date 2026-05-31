package com.albapay.backend.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "albapay")
@Getter
@Setter
public class AlbapayProperties {

    private Supabase supabase = new Supabase();
    private Toss toss = new Toss();
    private Nts nts = new Nts();
    private Cors cors = new Cors();

    @Getter @Setter
    public static class Supabase {
        private String url;
        private String serviceRoleKey;
    }

    @Getter @Setter
    public static class Toss {
        private String apiBaseUrl;
        private String mtlsCert;
        private String mtlsKey;
        private String mtlsCa;
        private String decryptionKey;
        private String decryptionAad;
        private String sessionSecret;
        private String callbackBasicUser;
        private String callbackBasicPassword;
    }

    @Getter @Setter
    public static class Nts {
        private String apiKey;
    }

    @Getter @Setter
    public static class Cors {
        private String allowedOrigins;
    }
}