package com.albapay.backend.toss;

import java.util.List;

public record TossLoginMeResponse(
        long userKey,
        String scope,
        List<String> agreedTerms,
        String name,
        String phone,
        String birthday,
        String ci,
        String gender,
        String nationality,
        String email
) {}
