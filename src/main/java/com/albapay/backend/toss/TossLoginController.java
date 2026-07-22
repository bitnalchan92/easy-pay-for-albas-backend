package com.albapay.backend.toss;

import com.albapay.backend.common.exception.BusinessException;
import com.albapay.backend.common.exception.ErrorCode;
import com.albapay.backend.config.AlbapayProperties;
import com.albapay.backend.supabase.SupabaseClient;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ports api/toss/login/{exchange,refresh,disconnect,callback}.ts. Each endpoint keeps the original's
 * "validate up front, then wrap the external calls in one catch-all" shape: BusinessExceptions thrown
 * during validation propagate untouched, anything else (Toss API / Supabase failure) collapses into
 * the endpoint's own 500 message, matching the single try/catch per handler in the TS source.
 *
 * {@code tossClient} is injected {@code @Lazy}: building it requires parsing the mTLS client
 * certificate (see TossConfig/TossMtlsSslContextFactory), which would otherwise run at application
 * startup and take down every other endpoint (health check, worker/workplace/payout CRUD, etc.) if
 * Toss certs are missing/invalid in a given environment. With @Lazy, that parsing is deferred until
 * the first actual call to one of these four endpoints, so a Toss misconfiguration only breaks Toss.
 */
@RestController
@RequestMapping("/toss/login")
@Slf4j
public class TossLoginController {

    private final TossClient tossClient;
    private final SupabaseClient supabaseClient;
    private final AlbapayProperties props;

    public TossLoginController(@Lazy TossClient tossClient, SupabaseClient supabaseClient, AlbapayProperties props) {
        this.tossClient = tossClient;
        this.supabaseClient = supabaseClient;
        this.props = props;
    }

    @PostMapping("/exchange")
    public Map<String, Object> exchange(@RequestBody Map<String, Object> body, HttpServletResponse response) {
        String authorizationCode = stringOrNull(body.get("authorizationCode"));
        String referrer = stringOrNull(body.get("referrer"));
        if (isBlank(authorizationCode) || isBlank(referrer)) {
            throw new BusinessException(ErrorCode.TOSS_MISSING_AUTH_CODE);
        }

        try {
            TossTokenResponse token = tossClient.generateToken(authorizationCode, referrer);
            TossLoginMeResponse loginMe = tossClient.loginMe(token.accessToken());

            AlbapayProperties.Toss tossProps = props.getToss();
            String name = decrypt(loginMe.name(), tossProps);
            String phone = decrypt(loginMe.phone(), tossProps);
            String email = decrypt(loginMe.email(), tossProps);
            String birthday = decrypt(loginMe.birthday(), tossProps);
            String gender = decrypt(loginMe.gender(), tossProps);
            String nationality = decrypt(loginMe.nationality(), tossProps);
            String ci = decrypt(loginMe.ci(), tossProps);

            Map<String, Object> userRow = new LinkedHashMap<>();
            userRow.put("user_key", loginMe.userKey());
            userRow.put("scopes", TossCrypto.splitCsv(loginMe.scope()));
            userRow.put("agreed_terms", loginMe.agreedTerms() != null ? loginMe.agreedTerms() : List.of());
            userRow.put("email", email);
            userRow.put("name", name);
            userRow.put("phone", phone);
            userRow.put("birthday", birthday);
            userRow.put("gender", gender);
            userRow.put("nationality", nationality);
            userRow.put("ci_encrypted", loginMe.ci());
            userRow.put("ci_hash", ci != null ? TossCrypto.sha256Hex(ci) : null);
            userRow.put("connected", true);
            userRow.put("last_login_at", Instant.now().toString());
            userRow.put("disconnected_at", null);

            supabaseClient.post(
                    "toss_login_users?on_conflict=user_key",
                    userRow,
                    new ParameterizedTypeReference<Void>() {},
                    "resolution=merge-duplicates,return=minimal");

            long expiresIn = Long.parseLong(token.expiresIn());
            Map<String, Object> tokenRow = new LinkedHashMap<>();
            tokenRow.put("user_key", loginMe.userKey());
            tokenRow.put("access_token", token.accessToken());
            tokenRow.put("refresh_token", token.refreshToken());
            tokenRow.put("access_token_expires_at", Instant.now().plusSeconds(expiresIn).toString());
            tokenRow.put("refresh_token_expires_at", Instant.now().plusSeconds(FOURTEEN_DAYS_SECONDS).toString());

            supabaseClient.post(
                    "toss_login_tokens?on_conflict=user_key",
                    tokenRow,
                    new ParameterizedTypeReference<Void>() {},
                    "resolution=merge-duplicates,return=minimal");

            String cookie = SessionCookie.buildSetCookieHeader(
                    loginMe.userKey(), tossProps.getSessionSecret(), tossProps.getDecryptionKey());
            if (cookie != null) response.addHeader("Set-Cookie", cookie);

            Map<String, Object> userResponse = new LinkedHashMap<>();
            userResponse.put("userKey", loginMe.userKey());
            userResponse.put("name", name);
            userResponse.put("phone", phone);
            userResponse.put("email", email);
            return Map.of("user", userResponse);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("[toss:exchange] failed", e);
            throw new BusinessException(ErrorCode.TOSS_EXCHANGE_FAILED);
        }
    }

    @PostMapping("/refresh")
    public Map<String, Object> refresh(@RequestBody Map<String, Object> body) {
        Long userKey = toLongOrNull(body.get("userKey"));
        if (userKey == null) throw new BusinessException(ErrorCode.TOSS_MISSING_USER_KEY);

        try {
            List<Map<String, Object>> rows = supabaseClient.get(
                    "toss_login_tokens?user_key=eq." + userKey + "&select=refresh_token",
                    new ParameterizedTypeReference<>() {});
            String refreshToken = rows == null || rows.isEmpty() ? null : (String) rows.get(0).get("refresh_token");
            if (refreshToken == null) throw new BusinessException(ErrorCode.TOSS_REFRESH_TOKEN_NOT_FOUND);

            TossTokenResponse token = tossClient.refreshToken(refreshToken);
            long expiresIn = Long.parseLong(token.expiresIn());

            Map<String, Object> patch = new LinkedHashMap<>();
            patch.put("access_token", token.accessToken());
            patch.put("refresh_token", token.refreshToken());
            patch.put("access_token_expires_at", Instant.now().plusSeconds(expiresIn).toString());
            patch.put("refresh_token_expires_at", Instant.now().plusSeconds(FOURTEEN_DAYS_SECONDS).toString());
            supabaseClient.patch(
                    "toss_login_tokens?user_key=eq." + userKey,
                    patch,
                    new ParameterizedTypeReference<Void>() {},
                    "return=minimal");

            return Map.of("ok", true);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("[toss:refresh] failed", e);
            throw new BusinessException(ErrorCode.TOSS_REFRESH_FAILED);
        }
    }

    @PostMapping("/disconnect")
    public Map<String, Object> disconnect(@RequestBody Map<String, Object> body, HttpServletResponse response) {
        Long userKey = toLongOrNull(body.get("userKey"));
        if (userKey == null) throw new BusinessException(ErrorCode.TOSS_MISSING_USER_KEY);

        try {
            List<Map<String, Object>> rows = supabaseClient.get(
                    "toss_login_tokens?user_key=eq." + userKey + "&select=access_token",
                    new ParameterizedTypeReference<>() {});
            String accessToken = rows == null || rows.isEmpty() ? null : (String) rows.get(0).get("access_token");
            if (accessToken == null) throw new BusinessException(ErrorCode.TOSS_ACCESS_TOKEN_NOT_FOUND);

            tossClient.disconnectByUserKey(accessToken, userKey);

            Map<String, Object> userPatch = new LinkedHashMap<>();
            userPatch.put("connected", false);
            userPatch.put("disconnected_at", Instant.now().toString());
            supabaseClient.patch(
                    "toss_login_users?user_key=eq." + userKey,
                    userPatch,
                    new ParameterizedTypeReference<Void>() {},
                    "return=minimal");
            supabaseClient.delete("toss_login_tokens?user_key=eq." + userKey);

            response.addHeader("Set-Cookie", SessionCookie.buildClearCookieHeader());
            return Map.of("ok", true);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("[toss:disconnect] failed", e);
            throw new BusinessException(ErrorCode.TOSS_DISCONNECT_FAILED);
        }
    }

    @RequestMapping(path = "/callback", method = {RequestMethod.GET, RequestMethod.POST})
    public Map<String, Object> callback(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(required = false) Map<String, String> queryParams,
            @RequestBody(required = false) Map<String, Object> body,
            HttpServletResponse response) {
        AlbapayProperties.Toss tossProps = props.getToss();
        if (!BasicAuthVerifier.verify(authorization, tossProps.getCallbackBasicUser(), tossProps.getCallbackBasicPassword())) {
            throw new BusinessException(ErrorCode.TOSS_CALLBACK_UNAUTHORIZED);
        }

        Map<String, Object> source = (body != null && !body.isEmpty())
                ? body
                : new LinkedHashMap<>(queryParams == null ? Map.of() : queryParams);

        Long userKey = toLongOrNull(source.get("userKey"));
        String referrer = stringOrNull(source.get("referrer"));
        if (userKey == null || isBlank(referrer)) {
            throw new BusinessException(ErrorCode.TOSS_MISSING_CALLBACK_FIELDS);
        }

        try {
            Map<String, Object> eventRow = new LinkedHashMap<>();
            eventRow.put("user_key", userKey);
            eventRow.put("referrer", referrer);
            eventRow.put("payload", Map.of("user_key", userKey, "referrer", referrer));
            eventRow.put("processed_at", Instant.now().toString());
            supabaseClient.post(
                    "toss_login_callback_events",
                    eventRow,
                    new ParameterizedTypeReference<Void>() {},
                    "return=minimal");

            Map<String, Object> userPatch = new LinkedHashMap<>();
            userPatch.put("connected", false);
            userPatch.put("disconnected_at", Instant.now().toString());
            supabaseClient.patch(
                    "toss_login_users?user_key=eq." + userKey,
                    userPatch,
                    new ParameterizedTypeReference<Void>() {},
                    "return=minimal");
            supabaseClient.delete("toss_login_tokens?user_key=eq." + userKey);
            supabaseClient.post(
                    "rpc/mark_account_withdrawal_toss_disconnected",
                    Map.of("p_user_key", userKey),
                    new ParameterizedTypeReference<Map<String, Object>>() {},
                    "return=representation");

            response.addHeader("Set-Cookie", SessionCookie.buildClearCookieHeader());
            return Map.of("ok", true);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("[toss:callback] failed", e);
            throw new BusinessException(ErrorCode.TOSS_CALLBACK_FAILED);
        }
    }

    private static final long FOURTEEN_DAYS_SECONDS = 14L * 24 * 60 * 60;

    private String decrypt(String encrypted, AlbapayProperties.Toss tossProps) {
        return TossCrypto.decryptField(encrypted, tossProps.getDecryptionKey(), tossProps.getDecryptionAad());
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String stringOrNull(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Long toLongOrNull(Object value) {
        if (value == null) return null;
        if (value instanceof Number number) return number.longValue();
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
