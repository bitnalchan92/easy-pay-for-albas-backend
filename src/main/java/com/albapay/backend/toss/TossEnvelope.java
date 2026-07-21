package com.albapay.backend.toss;

/** Wraps every apps-in-toss API response: {@code { resultType, success, error } }. */
public record TossEnvelope<T>(String resultType, T success, TossError error) {

    public boolean isSuccess() {
        return "SUCCESS".equals(resultType);
    }
}
