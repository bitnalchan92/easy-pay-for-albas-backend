package com.albapay.backend.toss;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

/**
 * Decrypts the AES-256-GCM encrypted fields (name/phone/email/... ) Toss returns from login-me,
 * mirroring `decryptTossField` in api/toss/_utils.ts. Layout: base64(iv[12] || ciphertext || tag[16]),
 * which is exactly what Java's "AES/GCM/NoPadding" cipher expects as a single doFinal() input
 * once the 12-byte IV is stripped off the front.
 */
public final class TossCrypto {

    private static final int IV_LENGTH = 12;
    private static final int TAG_LENGTH_BITS = 128;

    private TossCrypto() {}

    public static String decryptField(String encryptedValue, String base64Key, String aad) {
        if (encryptedValue == null || encryptedValue.isBlank()) return null;

        byte[] decoded = Base64.getDecoder().decode(encryptedValue);
        byte[] iv = Arrays.copyOfRange(decoded, 0, IV_LENGTH);
        byte[] cipherTextAndTag = Arrays.copyOfRange(decoded, IV_LENGTH, decoded.length);
        byte[] keyBytes = Base64.getDecoder().decode(base64Key);

        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    new SecretKeySpec(keyBytes, "AES"),
                    new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
            byte[] plain = cipher.doFinal(cipherTextAndTag);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Toss 필드 복호화에 실패했습니다.", e);
        }
    }

    public static String sha256Hex(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 해시 계산에 실패했습니다.", e);
        }
    }

    public static List<String> splitCsv(String value) {
        if (value == null || value.isBlank()) return List.of();
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
