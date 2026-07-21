package com.albapay.backend.toss;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/** Verifies the HTTP Basic auth header Toss sends on the disconnect-callback endpoint. */
public final class BasicAuthVerifier {

    private BasicAuthVerifier() {}

    public static boolean verify(String authorizationHeader, String expectedUser, String expectedPassword) {
        if (authorizationHeader == null || !authorizationHeader.startsWith("Basic ")) return false;

        String decoded;
        try {
            byte[] raw = Base64.getDecoder().decode(authorizationHeader.substring("Basic ".length()));
            decoded = new String(raw, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return false;
        }

        String expected = expectedUser + ":" + expectedPassword;
        return MessageDigest.isEqual(
                decoded.getBytes(StandardCharsets.UTF_8),
                expected.getBytes(StandardCharsets.UTF_8));
    }
}
