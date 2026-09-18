package com.albapay.backend.worker;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class WorkerCreationRemovalMigrationTest {
    private static final Path MIGRATION = Path.of(
            "supabase/migrations/20260918000000_remove_owner_worker_creation.sql");

    @Test
    void serviceRole권한회수후수동알바생생성함수를삭제한다() throws Exception {
        String sql = Files.readString(MIGRATION);
        String revoke = "revoke execute on function public.create_worker_by_owner(bigint,jsonb) from service_role";
        String drop = "drop function if exists public.create_worker_by_owner(bigint,jsonb)";

        assertThat(sql).contains(revoke, drop);
        assertThat(sql.indexOf(revoke)).isLessThan(sql.indexOf(drop));
    }
}
