package com.albapay.backend.worklog;

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

class WorklogControllerTest {
    @Test
    void 다른actor의근무일지생성은403이다() {
        SupabaseClient supabase = mock(SupabaseClient.class);
        WorklogController controller = new WorklogController(supabase);
        Map<String, Object> body = Map.of("worker_id", UUID.randomUUID(),
                "date", "2026-07-22", "authorizationCode", "code", "referrer", "SANDBOX");
        doReturn(Map.of("found", true, "authorized", false)).when(supabase)
                .post(eq("rpc/create_active_worklog"), any(), any(), any());

        assertThatThrownBy(() -> controller.create(99L, body))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.EMPLOYMENT_FORBIDDEN);
    }

    @Test
    void 다른actor의근무승인은403이다() {
        SupabaseClient supabase = mock(SupabaseClient.class);
        WorklogController controller = new WorklogController(supabase);
        Map<String, Object> body = Map.of("id", UUID.randomUUID(),
                "patch", Map.of("status", "approved"),
                "authorizationCode", "code", "referrer", "SANDBOX");
        doReturn(Map.of("found", true, "authorized", false)).when(supabase)
                .post(eq("rpc/transition_active_worklog"), any(), any(), any());

        assertThatThrownBy(() -> controller.patch(99L, body))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.EMPLOYMENT_FORBIDDEN);
    }
}
