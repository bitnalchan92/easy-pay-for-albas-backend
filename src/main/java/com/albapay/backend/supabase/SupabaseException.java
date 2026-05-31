package com.albapay.backend.supabase;

import com.albapay.backend.common.exception.BusinessException;
import com.albapay.backend.common.exception.ErrorCode;
import lombok.Getter;

@Getter
public class SupabaseException extends BusinessException {
    private final String detail;

    public SupabaseException(int statusCode, String body) {
        super(isConflict(statusCode, body) ? ErrorCode.SUPABASE_CONFLICT : ErrorCode.SUPABASE_ERROR);
        this.detail = body;
    }

    private static boolean isConflict(int statusCode, String body) {
        return statusCode == 409
                || body.contains("duplicate key")
                || body.contains("already exists");
    }
}
