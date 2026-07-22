package com.albapay.backend.workplace;

import com.albapay.backend.common.exception.BusinessException;
import com.albapay.backend.common.exception.ErrorCode;
import com.albapay.backend.supabase.SupabaseClient;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class WorkplaceControllerTest {
    @Test
    void bodyOwner는거부하고세션actor를Rpc에강제한다() {
        SupabaseClient supabase = mock(SupabaseClient.class);
        WorkplaceController controller = new WorkplaceController(supabase);

        assertThatThrownBy(() -> controller.create(77L, Map.of("name", "가게", "owner_toss_user_key", 88L)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.EMPLOYMENT_INVALID_REQUEST);
        verifyNoInteractions(supabase);

        doReturn(Map.of("id", "wp", "name", "가게")).when(supabase)
                .post(eq("rpc/create_workplace_for_actor"), any(), any(), any());
        controller.create(77L, Map.of("name", "가게", "invite_code", "ABC123"));
        verify(supabase).post(eq("rpc/create_workplace_for_actor"),
                eq(Map.of("p_actor_user_key", 77L,
                        "p_body", Map.of("name", "가게", "invite_code", "ABC123"))), any(), any());
    }
}
