package com.albapay.backend.payout;

import com.albapay.backend.supabase.SupabaseClient;
import com.albapay.backend.toss.TossClient;
import com.albapay.backend.toss.TossLoginMeResponse;
import com.albapay.backend.toss.TossTokenResponse;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PayoutServiceTest {
    @Test
    void 금액과사업장은받지않고검증된actor로지급Rpc를호출한다() {
        TossClient tossClient = mock(TossClient.class);
        SupabaseClient supabaseClient = mock(SupabaseClient.class);
        PayoutService service = new PayoutService(tossClient, supabaseClient);
        UUID workerId = UUID.randomUUID();
        when(tossClient.generateToken("code", "SANDBOX"))
                .thenReturn(new TossTokenResponse("Bearer", "token", "refresh", "3600", "scope"));
        when(tossClient.loginMe("token"))
                .thenReturn(new TossLoginMeResponse(99L, null, List.of(), null, null, null, null, null, null, null));
        doReturn(Map.of("found", true, "authorized", true, "allowed", true))
                .when(supabaseClient).post(eq("rpc/pay_worker_period"), any(), any(), any());

        Map<String, Object> result = service.pay(
                new PayoutRequest(workerId.toString(), "2026-06", "code", "SANDBOX", "PAYMENT_SENT"));

        assertThat(result).containsEntry("allowed", true);
        verify(supabaseClient).post(eq("rpc/pay_worker_period"), eq(Map.of(
                "p_actor_user_key", 99L, "p_worker_id", workerId, "p_period", "2026-06")), any(), any());
    }
}
