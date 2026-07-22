package com.albapay.backend.worker;

import com.albapay.backend.common.exception.BusinessException;
import com.albapay.backend.common.exception.ErrorCode;

import java.util.List;

public class WorkerDepartureBlockedException extends BusinessException {
    private final List<String> blockers;

    public WorkerDepartureBlockedException(List<String> blockers) {
        super(ErrorCode.WORKER_DEPARTURE_BLOCKED);
        this.blockers = List.copyOf(blockers);
    }

    public List<String> getBlockers() {
        return blockers;
    }
}
