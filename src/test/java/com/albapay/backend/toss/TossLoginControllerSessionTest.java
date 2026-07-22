package com.albapay.backend.toss;

import com.albapay.backend.common.exception.BusinessException;
import com.albapay.backend.common.exception.ErrorCode;
import com.albapay.backend.config.AlbapayProperties;
import com.albapay.backend.supabase.SupabaseClient;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class TossLoginControllerSessionTest {
    @Test
    void refresh와disconnect는bodyUserKey를거부한다() {
        TossClient tossClient = mock(TossClient.class);
        SupabaseClient supabase = mock(SupabaseClient.class);
        TossLoginController controller = new TossLoginController(tossClient, supabase, new AlbapayProperties());

        assertThatThrownBy(() -> controller.refresh(77L, Map.of("userKey", 88L)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.EMPLOYMENT_INVALID_REQUEST);
        assertThatThrownBy(() -> controller.disconnect(77L, Map.of("userKey", 88L),
                new org.springframework.mock.web.MockHttpServletResponse()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.EMPLOYMENT_INVALID_REQUEST);
        verifyNoInteractions(tossClient, supabase);
    }
}
