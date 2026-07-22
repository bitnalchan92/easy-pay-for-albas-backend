package com.albapay.backend.account;

import com.albapay.backend.common.exception.BusinessException;
import com.albapay.backend.common.exception.ErrorCode;
import lombok.Getter;

import java.util.List;
import java.util.Map;

/**
 * 409 WITHDRAWAL_BLOCKED. Extends BusinessException so the service's {@code catch (BusinessException) throw e}
 * lets it propagate untouched; a dedicated handler in GlobalExceptionHandler renders the blockers list
 * (the one case where the standard {code,message} error body is extended — see backend_handoff.md).
 */
@Getter
public class WithdrawalBlockedException extends BusinessException {

    private final List<Map<String, Object>> blockers;

    public WithdrawalBlockedException(List<Map<String, Object>> blockers) {
        super(ErrorCode.WITHDRAWAL_BLOCKED);
        this.blockers = blockers == null ? List.of() : blockers;
    }
}
