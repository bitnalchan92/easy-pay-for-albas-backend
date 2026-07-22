package com.albapay.backend.toss;

import com.albapay.backend.config.AlbapayProperties;
import com.albapay.backend.supabase.SupabaseClient;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@Component
public class TossSessionFilter extends OncePerRequestFilter {
    public static final String ACTOR_USER_KEY = "actorUserKey";

    private final AlbapayProperties properties;
    private final SupabaseClient supabaseClient;

    public TossSessionFilter(AlbapayProperties properties, SupabaseClient supabaseClient) {
        this.properties = properties;
        this.supabaseClient = supabaseClient;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        if (!requiresSession(request.getMethod(), path)) {
            chain.doFilter(request, response);
            return;
        }

        Long actor = SessionCookie.verify(cookieValue(request.getCookies()),
                properties.getToss().getSessionSecret(), properties.getToss().getDecryptionKey(),
                System.currentTimeMillis());
        if (actor == null || !isConnected(actor)) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("{\"code\":\"TOSS_SESSION_REQUIRED\",\"message\":\"토스 로그인이 필요합니다.\"}");
            return;
        }
        request.setAttribute(ACTOR_USER_KEY, actor);
        chain.doFilter(request, response);
    }

    static boolean requiresSession(String method, String path) {
        if ("OPTIONS".equals(method)) return false;
        if ("GET".equals(method) && "/health".equals(path)) return false;
        if ("POST".equals(method) && "/toss/login/exchange".equals(path)) return false;
        if ("POST".equals(method) && "/auth/owner-login".equals(path)) return false;
        if (("GET".equals(method) || "POST".equals(method)) && "/toss/login/callback".equals(path)) return false;
        if (!"POST".equals(method)) return true;
        return !("/account/withdraw".equals(path) || "/payouts".equals(path)
                || path.matches("/workers/[^/]+/departure"));
    }

    private boolean isConnected(long userKey) {
        List<Map<String, Object>> rows = supabaseClient.get(
                "toss_login_users?user_key=eq." + userKey + "&connected=eq.true&select=user_key&limit=1",
                new ParameterizedTypeReference<>() {});
        return rows != null && !rows.isEmpty();
    }

    private static String cookieValue(Cookie[] cookies) {
        if (cookies == null) return null;
        for (Cookie cookie : cookies) if ("albapay_session".equals(cookie.getName())) return cookie.getValue();
        return null;
    }
}
