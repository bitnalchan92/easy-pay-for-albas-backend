package com.albapay.backend.toss;

import com.albapay.backend.config.AlbapayProperties;
import com.albapay.backend.supabase.SupabaseClient;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class TossSessionFilterTest {
    @Test
    void endpoint예외목록외에는세션이필수다() {
        assertThat(TossSessionFilter.requiresSession("GET", "/health")).isFalse();
        assertThat(TossSessionFilter.requiresSession("POST", "/toss/login/exchange")).isFalse();
        assertThat(TossSessionFilter.requiresSession("POST", "/toss/login/callback")).isFalse();
        assertThat(TossSessionFilter.requiresSession("POST", "/account/withdraw")).isFalse();
        assertThat(TossSessionFilter.requiresSession("POST", "/workers/id/departure")).isFalse();
        assertThat(TossSessionFilter.requiresSession("POST", "/payouts")).isFalse();
        assertThat(TossSessionFilter.requiresSession("GET", "/data")).isTrue();
        assertThat(TossSessionFilter.requiresSession("POST", "/toss/login/refresh")).isTrue();
        assertThat(TossSessionFilter.requiresSession("POST", "/toss/login/disconnect")).isTrue();
        assertThat(TossSessionFilter.requiresSession("POST", "/workplaces")).isTrue();
        assertThat(TossSessionFilter.requiresSession("POST", "/verify-biz")).isTrue();
    }

    @Test
    void 유효하고연결된세션actor만요청에주입한다() throws Exception {
        AlbapayProperties properties = properties();
        SupabaseClient supabase = mock(SupabaseClient.class);
        doReturn(List.of(Map.of("user_key", 123L))).when(supabase)
                .get(startsWith("toss_login_users?user_key=eq.123"), any());
        TossSessionFilter filter = new TossSessionFilter(properties, supabase);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/data");
        request.setCookies(new Cookie("albapay_session", cookieValue(
                SessionCookie.buildSetCookieHeader(123L, "secret", null))));
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(request.getAttribute(TossSessionFilter.ACTOR_USER_KEY)).isEqualTo(123L);
        verify(chain).doFilter(request, response);
    }

    @Test
    void 세션이없으면401이고Db를호출하지않는다() throws Exception {
        SupabaseClient supabase = mock(SupabaseClient.class);
        TossSessionFilter filter = new TossSessionFilter(properties(), supabase);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/data");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        verifyNoInteractions(supabase, chain);
    }

    private static AlbapayProperties properties() {
        AlbapayProperties properties = new AlbapayProperties();
        properties.getToss().setSessionSecret("secret");
        return properties;
    }

    private static String cookieValue(String header) {
        return header.substring(header.indexOf('=') + 1, header.indexOf(';'));
    }
}
