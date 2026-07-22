package com.albapay.backend.worker;

import com.albapay.backend.common.exception.BusinessException;
import com.albapay.backend.common.exception.ErrorCode;
import com.albapay.backend.supabase.SupabaseClient;
import com.albapay.backend.toss.TossClient;
import com.albapay.backend.toss.TossLoginMeResponse;
import com.albapay.backend.toss.TossTokenResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WorkerDepartureServiceTest {
    @Mock TossClient tossClient;
    @Mock SupabaseClient supabaseClient;
    WorkerDepartureService service;

    @BeforeEach
    void setUp() {
        service = new WorkerDepartureService(tossClient, supabaseClient);
    }

    private void loginAs(long userKey) {
        when(tossClient.generateToken("code", "SANDBOX"))
                .thenReturn(new TossTokenResponse("Bearer", "token", "refresh", "3600", "scope"));
        when(tossClient.loginMe("token"))
                .thenReturn(new TossLoginMeResponse(userKey, null, List.of(), null, null, null, null, null, null, null));
    }

    @Test
    void loginMeActor만Rpc에전달한다() {
        UUID workerId = UUID.randomUUID();
        loginAs(99L);
        doReturn(Map.of("found", true, "authorized", true, "allowed", true, "reason", "owner_removed"))
                .when(supabaseClient).post(eq("rpc/depart_worker"), any(), any(), any());

        assertThat(service.depart(workerId.toString(), new DepartureRequest("code", "SANDBOX", "DEPART")))
                .isEqualTo("owner_removed");
        verify(supabaseClient).post(eq("rpc/depart_worker"),
                eq(Map.of("p_actor_user_key", 99L, "p_worker_id", workerId)), any(), any());
    }

    @Test
    void 미지급근무가있으면409로차단한다() {
        loginAs(99L);
        doReturn(Map.of("found", true, "authorized", true, "allowed", false,
                        "blockers", List.of("UNPAID_APPROVED_WORKLOGS")))
                .when(supabaseClient).post(eq("rpc/depart_worker"), any(), any(), any());

        assertThatThrownBy(() -> service.depart(UUID.randomUUID().toString(),
                new DepartureRequest("code", "SANDBOX", "DEPART")))
                .isInstanceOf(WorkerDepartureBlockedException.class);
    }

    @Test
    void bodyUserKey는요청단계에서거부한다() {
        assertThatThrownBy(() -> DepartureRequest.from(Map.of(
                "authorizationCode", "code", "referrer", "SANDBOX", "confirmation", "DEPART",
                "userKey", 1)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.DEPARTURE_INVALID_REQUEST);
    }
}
