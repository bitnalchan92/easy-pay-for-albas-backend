package com.albapay.backend.worklog;

import com.albapay.backend.common.exception.BusinessException;
import com.albapay.backend.common.exception.ErrorCode;
import com.albapay.backend.push.PushService;
import com.albapay.backend.supabase.SupabaseClient;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class WorklogControllerTest {
    @Test
    void 생성성공후내부알림정보를제거하고발송한다() {
        SupabaseClient supabase = mock(SupabaseClient.class);
        PushService pushService = mock(PushService.class);
        WorklogController controller = new WorklogController(supabase, pushService);
        Map<String, Object> notification = Map.of("event", "worklog_submitted");
        doReturn(Map.of(
                "id", UUID.randomUUID(),
                "authorized", true,
                "notification", notification)).when(supabase)
                .post(eq("rpc/create_active_worklog"), any(), any(), any());

        Map<String, Object> response = controller.create(99L, Map.of()).getBody();

        assertThat(response).doesNotContainKeys("authorized", "notification");
        verify(pushService).send(notification);
    }

    @Test
    void 다른actor의근무일지생성은403이다() {
        SupabaseClient supabase = mock(SupabaseClient.class);
        WorklogController controller = new WorklogController(supabase, mock(PushService.class));
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
        WorklogController controller = new WorklogController(supabase, mock(PushService.class));
        Map<String, Object> body = Map.of("id", UUID.randomUUID(),
                "patch", Map.of("status", "approved"),
                "authorizationCode", "code", "referrer", "SANDBOX");
        doReturn(Map.of("found", true, "authorized", false)).when(supabase)
                .post(eq("rpc/transition_active_worklog"), any(), any(), any());

        assertThatThrownBy(() -> controller.patch(99L, body))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.EMPLOYMENT_FORBIDDEN);
    }

    @Test
    void 처리성공후알바생에게결과를발송한다() {
        SupabaseClient supabase = mock(SupabaseClient.class);
        PushService pushService = mock(PushService.class);
        WorklogController controller = new WorklogController(supabase, pushService);
        Map<String, Object> notification = Map.of("event", "worklog_reviewed");
        doReturn(Map.of("found", true, "authorized", true, "notification", notification))
                .when(supabase).post(eq("rpc/transition_active_worklog"), any(), any(), any());

        controller.patch(99L, Map.of(
                "id", UUID.randomUUID(),
                "patch", Map.of("status", "approved")));

        verify(pushService).send(notification);
    }
}
