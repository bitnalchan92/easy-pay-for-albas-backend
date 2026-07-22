package com.albapay.backend.toss;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SessionCookieTest {
    private static final String SECRET = "test-session-secret";

    @Test
    void 정상쿠키는userKey를복원한다() {
        String value = cookieValue(SessionCookie.buildSetCookieHeader(123L, SECRET, null));

        assertThat(SessionCookie.verify(value, SECRET, null, System.currentTimeMillis())).isEqualTo(123L);
    }

    @Test
    void 변조된쿠키는거부한다() {
        String value = cookieValue(SessionCookie.buildSetCookieHeader(123L, SECRET, null));
        String tampered = value.substring(0, value.length() - 1) + (value.endsWith("A") ? "B" : "A");

        assertThat(SessionCookie.verify(tampered, SECRET, null, System.currentTimeMillis())).isNull();
    }

    @Test
    void 만료되거나없는쿠키는거부한다() {
        String value = cookieValue(SessionCookie.buildSetCookieHeader(123L, SECRET, null));
        long fifteenDaysLater = System.currentTimeMillis() + 15L * 24 * 60 * 60 * 1000;

        assertThat(SessionCookie.verify(value, SECRET, null, fifteenDaysLater)).isNull();
        assertThat(SessionCookie.verify(null, SECRET, null, System.currentTimeMillis())).isNull();
    }

    @Test
    void crossOrigin쿠키속성을사용한다() {
        assertThat(SessionCookie.buildSetCookieHeader(123L, SECRET, null))
                .contains("HttpOnly", "Secure", "SameSite=None", "Partitioned");
    }

    private static String cookieValue(String header) {
        return header.substring(header.indexOf('=') + 1, header.indexOf(';'));
    }
}
