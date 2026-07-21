package com.albapay.backend.toss;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Signs/clears the `albapay_session` cookie, mirroring setSessionCookie/clearSessionCookie
 * in api/toss/_utils.ts: base64url(JSON{userKey,iat}) + "." + HMAC-SHA256(payload), base64url.
 */
public final class SessionCookie {

    private static final String COOKIE_NAME = "albapay_session";
    private static final long MAX_AGE_SECONDS = 1209600; // 14 days

    private SessionCookie() {}

    /** Returns null (no header to set) if neither sessionSecret nor the decryption-key fallback is configured. */
    public static String buildSetCookieHeader(long userKey, String sessionSecret, String decryptionKeyFallback) {
        String secret = (sessionSecret != null && !sessionSecret.isBlank())
                ? sessionSecret
                : decryptionKeyFallback;
        if (secret == null || secret.isBlank()) return null;

        String json = "{\"userKey\":" + userKey + ",\"iat\":" + System.currentTimeMillis() + "}";
        String payload = base64Url(json.getBytes(StandardCharsets.UTF_8));
        String signature = base64Url(hmacSha256(secret, payload));

        return COOKIE_NAME + "=" + payload + "." + signature
                + "; HttpOnly; Secure; SameSite=Lax; Path=/; Max-Age=" + MAX_AGE_SECONDS;
    }

    public static String buildClearCookieHeader() {
        return COOKIE_NAME + "=; HttpOnly; Secure; SameSite=Lax; Path=/; Max-Age=0";
    }

    private static byte[] hmacSha256(String secret, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("세션 쿠키 서명에 실패했습니다.", e);
        }
    }

    private static String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
