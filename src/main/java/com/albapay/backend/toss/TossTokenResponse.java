package com.albapay.backend.toss;

/** {@code expiresIn} is kept as String since Toss may send it as either a JSON string or number. */
public record TossTokenResponse(
        String tokenType,
        String accessToken,
        String refreshToken,
        String expiresIn,
        String scope
) {}
