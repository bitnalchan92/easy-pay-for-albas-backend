package com.albapay.backend.auth;

import com.albapay.backend.push.PushService;
import com.albapay.backend.supabase.SupabaseClient;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AuthControllerTest {
    @Test
    void 새가입알림을사장에게발송한다() {
        SupabaseClient supabase = mock(SupabaseClient.class);
        PushService pushService = mock(PushService.class);
        AuthController controller = new AuthController(supabase, pushService);
        Map<String, Object> notification = Map.of("event", "worker_joined");
        doReturn(Map.of(
                "found", true,
                "worker", Map.of("status", "pending"),
                "workplace", Map.of("name", "매장"),
                "notification", notification)).when(supabase)
                .post(eq("rpc/join_worker_by_toss"), any(), any(), any());

        controller.workerJoin(7L, Map.of(
                "inviteCode", "abc",
                "name", "홍길동",
                "authMethod", "toss"));

        verify(pushService).send(notification);
    }
}
