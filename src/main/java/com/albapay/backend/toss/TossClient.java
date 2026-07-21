package com.albapay.backend.toss;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * Thin wrapper over the apps-in-toss OAuth endpoints, mirroring `tossRequest` in the
 * original api/toss/_utils.ts. Any failure (HTTP error or {@code resultType: "FAIL"} envelope)
 * throws a plain unchecked exception rather than a {@link com.albapay.backend.common.exception.BusinessException} —
 * callers (TossLoginController) catch it and translate to the per-endpoint error message,
 * matching the single catch-all per handler in the original TypeScript.
 *
 * {@code @Lazy}: this bean (and the mTLS-parsing {@code tossRestClient} it depends on, see
 * TossConfig) must not be part of Spring's normal eager singleton pre-instantiation — otherwise
 * missing/invalid Toss certs would crash the whole app at startup instead of only the Toss endpoints.
 */
@Lazy
@Service
@RequiredArgsConstructor
public class TossClient {

    private static final String GENERATE_TOKEN_PATH =
            "/api-partner/v1/apps-in-toss/user/oauth2/generate-token";
    private static final String LOGIN_ME_PATH =
            "/api-partner/v1/apps-in-toss/user/oauth2/login-me";
    private static final String REFRESH_TOKEN_PATH =
            "/api-partner/v1/apps-in-toss/user/oauth2/refresh-token";
    private static final String REMOVE_BY_USER_KEY_PATH =
            "/api-partner/v1/apps-in-toss/user/oauth2/access/remove-by-user-key";

    private final RestClient tossRestClient;

    public TossTokenResponse generateToken(String authorizationCode, String referrer) {
        return request(
                GENERATE_TOKEN_PATH,
                HttpMethod.POST,
                Map.of("authorizationCode", authorizationCode, "referrer", referrer),
                null,
                new ParameterizedTypeReference<TossEnvelope<TossTokenResponse>>() {});
    }

    public TossLoginMeResponse loginMe(String accessToken) {
        return request(
                LOGIN_ME_PATH, HttpMethod.GET, null, accessToken,
                new ParameterizedTypeReference<TossEnvelope<TossLoginMeResponse>>() {});
    }

    public TossTokenResponse refreshToken(String refreshToken) {
        return request(
                REFRESH_TOKEN_PATH,
                HttpMethod.POST,
                Map.of("refreshToken", refreshToken),
                null,
                new ParameterizedTypeReference<TossEnvelope<TossTokenResponse>>() {});
    }

    public void disconnectByUserKey(String accessToken, long userKey) {
        request(
                REMOVE_BY_USER_KEY_PATH,
                HttpMethod.POST,
                Map.of("userKey", userKey),
                accessToken,
                new ParameterizedTypeReference<TossEnvelope<Object>>() {});
    }

    private <T> T request(
            String path,
            HttpMethod method,
            Object body,
            String accessToken,
            ParameterizedTypeReference<TossEnvelope<T>> type) {
        try {
            RestClient.RequestHeadersSpec<?> spec =
                    method == HttpMethod.GET
                            ? tossRestClient.get().uri(path)
                            : tossRestClient.post().uri(path).body(body);
            if (accessToken != null) {
                spec = spec.header("Authorization", "Bearer " + accessToken);
            }

            TossEnvelope<T> envelope = spec.retrieve().body(type);
            if (envelope == null || !envelope.isSuccess()) {
                String reason = envelope != null && envelope.error() != null
                        ? envelope.error().messageOrDefault()
                        : "Toss API 요청에 실패했습니다.";
                throw new IllegalStateException(reason);
            }
            return envelope.success();
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Toss API 호출에 실패했습니다: " + path, e);
        }
    }
}
