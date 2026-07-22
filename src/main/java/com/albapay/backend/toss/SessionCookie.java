package com.albapay.backend.toss;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Signs/clears the `albapay_session` cookie, mirroring setSessionCookie/clearSessionCookie
 * in api/toss/_utils.ts: base64url(JSON{userKey,iat}) + "." + HMAC-SHA256(payload), base64url.
 */
public final class SessionCookie {

    private static final String COOKIE_NAME = "albapay_session";
    private static final long MAX_AGE_SECONDS = 1209600; // 14 days
    private static final Pattern PAYLOAD = Pattern.compile("\\{\"userKey\":(-?\\d+),\"iat\":(\\d+)\\}");

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
                + "; HttpOnly; Secure; SameSite=None; Partitioned; Path=/; Max-Age=" + MAX_AGE_SECONDS;
    }

    public static String buildClearCookieHeader() {
        return COOKIE_NAME + "=; HttpOnly; Secure; SameSite=None; Partitioned; Path=/; Max-Age=0";
    }

    public static Long verify(String value, String sessionSecret, String decryptionKeyFallback, long nowMillis) {
        String secret = secret(sessionSecret, decryptionKeyFallback);
        if (value == null || secret == null) return null;
        String[] parts = value.split("\\.", -1);
        if (parts.length != 2) return null;
        try {
            byte[] supplied = Base64.getUrlDecoder().decode(parts[1]);
            if (!base64Url(supplied).equals(parts[1])) return null;
            byte[] expected = hmacSha256(secret, parts[0]);
            if (!MessageDigest.isEqual(supplied, expected)) return null;
            byte[] payload = Base64.getUrlDecoder().decode(parts[0]);
            if (!base64Url(payload).equals(parts[0])) return null;
            String json = new String(payload, StandardCharsets.UTF_8);
            Matcher matcher = PAYLOAD.matcher(json);
            if (!matcher.matches()) return null;
            long issuedAt = Long.parseLong(matcher.group(2));
            if (issuedAt > nowMillis || nowMillis - issuedAt > MAX_AGE_SECONDS * 1000) return null;
            return Long.parseLong(matcher.group(1));
        } catch (IllegalArgumentException e) {
            return null;
        }
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

    private static String secret(String sessionSecret, String decryptionKeyFallback) {
        String value = sessionSecret != null && !sessionSecret.isBlank() ? sessionSecret : decryptionKeyFallback;
        return value == null || value.isBlank() ? null : value;
    }
}
