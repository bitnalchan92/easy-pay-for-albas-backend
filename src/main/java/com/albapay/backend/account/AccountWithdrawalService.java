package com.albapay.backend.account;

import com.albapay.backend.common.exception.BusinessException;
import com.albapay.backend.common.exception.ErrorCode;
import com.albapay.backend.supabase.SupabaseClient;
import com.albapay.backend.toss.TossClient;
import com.albapay.backend.toss.TossLoginMeResponse;
import com.albapay.backend.toss.TossTokenResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 알바페이 회원 탈퇴 오케스트레이션. 판정과 DB 변경은 Supabase RPC(prepare/finalize) 안에서 transaction과
 * advisory lock으로 처리하고, Spring은 그 사이에 낄 수 없는 Toss 외부 호출(재인증·연결 해제)만 담당한다.
 *
 * 흐름: body 선검증 → Toss token 교환 + login-me로 userKey 확정 → prepare RPC(blocker면 409) →
 * (아직 해제 안 됐으면) Toss disconnect → finalize RPC → 성공 시에만 200.
 * userKey는 오직 login-me 결과에서만 나온다. body의 어떤 식별자도 신뢰하지 않는다.
 */
@Service
@Slf4j
public class AccountWithdrawalService {

    private static final String CONFIRMATION = "WITHDRAW";
    private static final ParameterizedTypeReference<Map<String, Object>> RPC_RESULT =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<List<Map<String, Object>>> ROWS =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<Void> VOID =
            new ParameterizedTypeReference<>() {};

    private final TossClient tossClient;
    private final SupabaseClient supabaseClient;

    public AccountWithdrawalService(@Lazy TossClient tossClient, SupabaseClient supabaseClient) {
        this.tossClient = tossClient;
        this.supabaseClient = supabaseClient;
    }

    public void withdraw(WithdrawRequest req) {
        validate(req); // 외부 API/DB 호출 전에 검증

        long userKey;
        String accessToken;
        try {
            TossTokenResponse token = tossClient.generateToken(req.authorizationCode(), req.referrer());
            TossLoginMeResponse me = tossClient.loginMe(token.accessToken());
            accessToken = token.accessToken();
            userKey = me.userKey();
        } catch (Exception e) {
            log.error("[account:withdraw] toss 재인증 실패", e);
            throw new BusinessException(ErrorCode.WITHDRAW_AUTH_FAILED);
        }

        PrepareResult prep = prepare(userKey);
        if (!prep.allowed()) {
            // blocker면 job/개인정보/토큰을 건드리지 않고 즉시 409. Toss disconnect 금지.
            throw new WithdrawalBlockedException(prep.blockers());
        }

        if (!prep.tossDisconnected()) {
            try {
                tossClient.disconnectByUserKey(accessToken, userKey);
            } catch (Exception e) {
                // disconnect 실패 시 finalize하지 않고 job을 pending/last_error로 남긴다.
                // ponytail: 이미 해제됨/token 없음을 Toss 에러 문자열로 자동 판별하진 않는다.
                // 그 멱등 수렴은 job.toss_disconnected를 통한 운영 재처리(finalizePendingWithdrawal)로 해결한다.
                recordJobError(userKey, "toss_disconnect_failed");
                log.error("[account:withdraw] toss disconnect 실패, job pending 유지", e);
                throw new BusinessException(ErrorCode.WITHDRAW_TOSS_DISCONNECT_FAILED);
            }
            markTossDisconnected(userKey);
        }

        finalizeWithdrawal(userKey);
    }

    /**
     * 운영 재처리 경로. disconnect 성공 뒤 DB finalize가 실패해 pending으로 남은 job을, body userKey나
     * 비밀값 없이 내부에서 다시 finalize한다. finalize RPC가 멱등(completed면 no-op)이라 completed로 수렴한다.
     */
    public boolean finalizePendingWithdrawal(long userKey) {
        List<Map<String, Object>> rows = supabaseClient.get(
                "account_withdrawal_jobs?user_key=eq." + userKey
                        + "&status=eq.pending&toss_disconnected=eq.true&select=user_key&limit=1",
                ROWS);
        if (rows == null || rows.isEmpty()) return false;
        finalizeWithdrawal(userKey);
        return true;
    }

    /** 공개 endpoint 없이, Toss 해제가 확인된 pending job만 자동 재처리한다. */
    @Scheduled(
            fixedDelayString = "${albapay.withdrawal-retry-ms:60000}",
            initialDelayString = "${albapay.withdrawal-retry-ms:60000}")
    public void retryDisconnectedPendingWithdrawals() {
        List<Map<String, Object>> rows = supabaseClient.get(
                "account_withdrawal_jobs?status=eq.pending&toss_disconnected=eq.true&select=user_key&limit=100",
                ROWS);
        if (rows == null) return;
        for (Map<String, Object> row : rows) {
            Object value = row.get("user_key");
            if (!(value instanceof Number number)) continue;
            try {
                finalizePendingWithdrawal(number.longValue());
            } catch (Exception e) {
                // ponytail: 100건 주기 스캔, 작업량이 커지면 DB queue/claim 방식으로 교체.
                log.warn("[account:withdraw] 연결 해제 완료 job 재처리 실패");
            }
        }
    }

    private void validate(WithdrawRequest req) {
        if (req == null
                || isBlank(req.authorizationCode())
                || isBlank(req.referrer())
                || !CONFIRMATION.equals(req.confirmation())) {
            throw new BusinessException(ErrorCode.WITHDRAW_INVALID_REQUEST);
        }
    }

    private PrepareResult prepare(long userKey) {
        Map<String, Object> res = supabaseClient.post(
                "rpc/prepare_account_withdrawal",
                Map.of("p_user_key", userKey),
                RPC_RESULT,
                "return=representation");
        if (res == null) {
            throw new BusinessException(ErrorCode.WITHDRAW_FINALIZE_FAILED);
        }
        boolean allowed = Boolean.TRUE.equals(res.get("allowed"));
        boolean tossDisconnected = Boolean.TRUE.equals(res.get("tossDisconnected"));
        Object blockers = res.get("blockers");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> blockerList =
                blockers instanceof List<?> ? (List<Map<String, Object>>) blockers : List.of();
        return new PrepareResult(allowed, tossDisconnected, blockerList);
    }

    private void finalizeWithdrawal(long userKey) {
        try {
            supabaseClient.post(
                    "rpc/finalize_account_withdrawal",
                    Map.of("p_user_key", userKey),
                    RPC_RESULT,
                    "return=representation");
        } catch (Exception e) {
            recordJobError(userKey, "finalize_failed");
            log.error("[account:withdraw] finalize 실패, job pending 유지", e);
            throw new BusinessException(ErrorCode.WITHDRAW_FINALIZE_FAILED);
        }
    }

    private void markTossDisconnected(long userKey) {
        supabaseClient.post(
                "rpc/mark_account_withdrawal_toss_disconnected",
                Map.of("p_user_key", userKey),
                RPC_RESULT,
                "return=representation");
    }

    private void recordJobError(long userKey, String error) {
        try {
            supabaseClient.patch(
                    "account_withdrawal_jobs?user_key=eq." + userKey,
                    Map.of("last_error", error, "updated_at", Instant.now().toString()),
                    VOID,
                    "return=minimal");
        } catch (Exception e) {
            log.warn("[account:withdraw] job last_error 기록 실패", e);
        }
    }

    private static boolean isBlank(String v) {
        return v == null || v.isBlank();
    }

    /** prepare RPC 결과의 판정만 담는 내부 뷰. */
    record PrepareResult(boolean allowed, boolean tossDisconnected, List<Map<String, Object>> blockers) {}
}
