package com.albapay.backend.worker;

import com.albapay.backend.common.exception.BusinessException;
import com.albapay.backend.common.exception.ErrorCode;
import com.albapay.backend.supabase.SupabaseClient;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

class WorkerControllerTest {
    @Test
    void 다른actor의알바생직접추가는403이다() {
        SupabaseClient supabase = mock(SupabaseClient.class);
        WorkerController controller = new WorkerController(supabase);
        Map<String, Object> body = Map.of("workplace_id", UUID.randomUUID(), "name", "테스트",
                "authorizationCode", "code", "referrer", "SANDBOX");
        doReturn(Map.of("found", true, "authorized", false)).when(supabase)
                .post(eq("rpc/create_worker_by_owner"), any(), any(), any());

        assertThatThrownBy(() -> controller.create(99L, body))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.EMPLOYMENT_FORBIDDEN);
    }

    @Test
    void 다른actor의근무조건변경은403이다() {
        SupabaseClient supabase = mock(SupabaseClient.class);
        WorkerController controller = new WorkerController(supabase);
        Map<String, Object> body = Map.of("id", UUID.randomUUID(),
                "patch", Map.of("hourly_wage", 12000),
                "authorizationCode", "code", "referrer", "SANDBOX");
        doReturn(Map.of("found", true, "authorized", false)).when(supabase)
                .post(eq("rpc/patch_active_worker"), any(), any(), any());

        assertThatThrownBy(() -> controller.patch(99L, body))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.EMPLOYMENT_FORBIDDEN);
    }
}
