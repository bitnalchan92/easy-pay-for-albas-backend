package com.albapay.backend.account;

import com.albapay.backend.common.exception.BusinessException;
import com.albapay.backend.common.exception.ErrorCode;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** body는 허용된 3개 필드만 받고, userKey 등 미지의 식별자는 400을 유발한다는 계약을 확인한다. */
class WithdrawRequestTest {

    @Test
    void acceptsExactlyTheThreeFields() {
        WithdrawRequest r = WithdrawRequest.from(Map.of(
                "authorizationCode", "c", "referrer", "SANDBOX", "confirmation", "WITHDRAW"));
        assertThat(r.authorizationCode()).isEqualTo("c");
        assertThat(r.referrer()).isEqualTo("SANDBOX");
        assertThat(r.confirmation()).isEqualTo("WITHDRAW");
    }

    @Test
    void rejectsExtraUserKeyField() {
        Map<String, Object> body = new HashMap<>(Map.of(
                "authorizationCode", "c", "referrer", "SANDBOX", "confirmation", "WITHDRAW"));
        body.put("userKey", 123);
        assertThatThrownBy(() -> WithdrawRequest.from(body))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.WITHDRAW_INVALID_REQUEST);
    }

    @Test
    void rejectsUserKeyOnlyBody() {
        assertThatThrownBy(() -> WithdrawRequest.from(Map.of("userKey", 123)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.WITHDRAW_INVALID_REQUEST);
    }
}
