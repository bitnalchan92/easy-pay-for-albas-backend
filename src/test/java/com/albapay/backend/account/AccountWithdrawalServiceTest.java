package com.albapay.backend.account;

import com.albapay.backend.common.exception.BusinessException;
import com.albapay.backend.common.exception.ErrorCode;
import com.albapay.backend.supabase.SupabaseClient;
import com.albapay.backend.toss.TossClient;
import com.albapay.backend.toss.TossLoginMeResponse;
import com.albapay.backend.toss.TossTokenResponse;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 회원 탈퇴 오케스트레이션의 Java 레벨 정책만 검증한다. (worker 익명화·rollback 등 DB 정책은 RPC/SQL에서 강제됨) */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AccountWithdrawalServiceTest {

    @Mock TossClient tossClient;
    @Mock SupabaseClient supabaseClient;
    @Mock HttpServletResponse response;

    AccountWithdrawalService service;

    @BeforeEach
    void setUp() {
        service = new AccountWithdrawalService(tossClient, supabaseClient);
    }

    private static WithdrawRequest validReq() {
        return new WithdrawRequest("one-time-code", "SANDBOX", "WITHDRAW");
    }

    private void stubTossLogin(long userKey) {
        when(tossClient.generateToken(any(), any()))
                .thenReturn(new TossTokenResponse("Bearer", "access-tok", "refresh-tok", "3600", "scope"));
        when(tossClient.loginMe("access-tok"))
                .thenReturn(new TossLoginMeResponse(userKey, null, List.of(), null, null, null, null, null, null, null));
    }

    private void stubPrepare(boolean allowed, boolean tossDisconnected, List<Map<String, Object>> blockers) {
        Map<String, Object> res = Map.of(
                "allowed", allowed, "tossDisconnected", tossDisconnected, "blockers", blockers);
        doReturn(res).when(supabaseClient)
                .post(eq("rpc/prepare_account_withdrawal"), any(), any(), any());
    }

    @Test
    void confirmationError_noTossOrDbCalls() {
        WithdrawRequest bad = new WithdrawRequest("code", "SANDBOX", "NOPE");
        assertThatThrownBy(() -> service.withdraw(bad))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.WITHDRAW_INVALID_REQUEST);
        verifyNoInteractions(tossClient, supabaseClient);
    }

    @Test
    void blankAuthorizationCode_noTossOrDbCalls() {
        WithdrawRequest bad = new WithdrawRequest("  ", "SANDBOX", "WITHDRAW");
        assertThatThrownBy(() -> service.withdraw(bad)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(tossClient, supabaseClient);
    }

    @Test
    void prepareBlockers_409_noDisconnect_noFinalize() {
        stubTossLogin(999L);
        stubPrepare(false, false, List.of(Map.of("code", "OWNED_WORKPLACE_NOT_EMPTY", "workplaceId", "wp-1")));

        assertThatThrownBy(() -> service.withdraw(validReq()))
                .isInstanceOf(WithdrawalBlockedException.class)
                .satisfies(e -> assertThat(((WithdrawalBlockedException) e).getBlockers()).hasSize(1));

        verify(tossClient, never()).disconnectByUserKey(any(), anyLong());
        verify(supabaseClient, never()).post(eq("rpc/finalize_account_withdrawal"), any(), any(), any());
    }

    @Test
    void usesLoginMeUserKey_notBody() {
        stubTossLogin(999L);
        stubPrepare(true, false, List.of());

        service.withdraw(validReq());

        verify(tossClient).disconnectByUserKey("access-tok", 999L);
        verify(supabaseClient).post(eq("rpc/prepare_account_withdrawal"), eq(Map.of("p_user_key", 999L)), any(), any());
        verify(supabaseClient).post(eq("rpc/finalize_account_withdrawal"), eq(Map.of("p_user_key", 999L)), any(), any());
    }

    @Test
    void disconnectFailure_noFinalize_jobKeptPending() {
        stubTossLogin(999L);
        stubPrepare(true, false, List.of());
        doThrow(new IllegalStateException("toss down")).when(tossClient).disconnectByUserKey(any(), anyLong());

        assertThatThrownBy(() -> service.withdraw(validReq()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.WITHDRAW_TOSS_DISCONNECT_FAILED);

        verify(supabaseClient, never()).post(eq("rpc/finalize_account_withdrawal"), any(), any(), any());
        verify(supabaseClient).patch(
                startsWith("account_withdrawal_jobs?user_key=eq.999"),
                argThat(b -> b instanceof Map<?, ?> m && m.containsKey("last_error")),
                any(), any());

        doReturn(List.of()).when(supabaseClient).get(
                contains("toss_disconnected=eq.true"), any());
        assertThat(service.finalizePendingWithdrawal(999L)).isFalse();
        verify(supabaseClient, never()).post(eq("rpc/finalize_account_withdrawal"), any(), any(), any());
    }

    @Test
    void alreadyTossDisconnected_skipsDisconnect_finalizes() {
        stubTossLogin(999L);
        stubPrepare(true, true, List.of());

        service.withdraw(validReq());

        verify(tossClient, never()).disconnectByUserKey(any(), anyLong());
        verify(supabaseClient).post(eq("rpc/finalize_account_withdrawal"), any(), any(), any());
    }

    @Test
    void finalizeFailure_thenOpsReprocess_converges() {
        stubTossLogin(999L);
        stubPrepare(true, false, List.of());
        doThrow(new IllegalStateException("db")).doReturn(Map.of("ok", true))
                .when(supabaseClient).post(eq("rpc/finalize_account_withdrawal"), any(), any(), any());

        assertThatThrownBy(() -> service.withdraw(validReq()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.WITHDRAW_FINALIZE_FAILED);

        // 비밀값·body userKey 없이 내부 재처리 → completed로 수렴
        doReturn(List.of(Map.of("user_key", 999L))).when(supabaseClient).get(
                contains("user_key=eq.999"), any());
        assertThat(service.finalizePendingWithdrawal(999L)).isTrue();

        verify(supabaseClient, times(2)).post(eq("rpc/finalize_account_withdrawal"), any(), any(), any());
    }

    @Test
    void schedulerFinalizesOnlyDisconnectedPendingJobs() {
        doReturn(List.of(Map.of("user_key", 999L))).when(supabaseClient).get(
                startsWith("account_withdrawal_jobs?status=eq.pending"), any());
        doReturn(List.of(Map.of("user_key", 999L))).when(supabaseClient).get(
                contains("user_key=eq.999"), any());

        service.retryDisconnectedPendingWithdrawals();

        verify(supabaseClient).post(
                eq("rpc/finalize_account_withdrawal"), eq(Map.of("p_user_key", 999L)), any(), any());
    }

    @Test
    void success_controllerSetsClearCookie() {
        stubTossLogin(999L);
        stubPrepare(true, false, List.of());
        AccountWithdrawalController controller = new AccountWithdrawalController(service);

        Map<String, Object> body = controller.withdraw(
                Map.of("authorizationCode", "one-time-code", "referrer", "SANDBOX", "confirmation", "WITHDRAW"),
                response);

        assertThat(body).containsEntry("ok", true);
        verify(response).addHeader(eq("Set-Cookie"), argThat(v -> v.contains("Max-Age=0")));
    }
}
