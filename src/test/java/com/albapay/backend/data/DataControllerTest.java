package com.albapay.backend.data;

import com.albapay.backend.supabase.SupabaseClient;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class DataControllerTest {
    @Test
    void 세션actor만필터링Rpc에전달한다() {
        SupabaseClient supabase = mock(SupabaseClient.class);
        DataController controller = new DataController(supabase);
        doReturn(Map.of("workplaces", java.util.List.of(), "workers", java.util.List.of(),
                "worklogs", java.util.List.of(), "payouts", java.util.List.of()))
                .when(supabase).post(eq("rpc/get_actor_data"), any(), any(), any());

        assertThat(controller.getData(77L).getBody()).containsKey("workplaces");
        verify(supabase).post(eq("rpc/get_actor_data"), eq(Map.of("p_actor_user_key", 77L)), any(), any());
    }
}
