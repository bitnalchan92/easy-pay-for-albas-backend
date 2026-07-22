package com.albapay.backend.toss;

import com.albapay.backend.common.exception.BusinessException;
import com.albapay.backend.common.exception.ErrorCode;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class TossActorServiceTest {
    @Test
    void body사용자키는인증전에거부한다() {
        TossClient tossClient = mock(TossClient.class);
        TossActorService service = new TossActorService(tossClient);

        assertThatThrownBy(() -> service.authenticate(Map.of(
                "authorizationCode", "code", "referrer", "SANDBOX", "userKey", 7)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.EMPLOYMENT_INVALID_REQUEST);
        verifyNoInteractions(tossClient);
    }
}
