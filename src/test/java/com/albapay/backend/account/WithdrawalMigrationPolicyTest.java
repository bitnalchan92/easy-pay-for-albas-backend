package com.albapay.backend.account;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class WithdrawalMigrationPolicyTest {
    private static final Path MIGRATION = Path.of(
            "supabase/migrations/20260722020000_worker_departure_and_archival.sql");

    @Test
    void prepare는workplaceWorkerWorklog순서로잠근뒤job을생성한다() throws Exception {
        String sql = Files.readString(MIGRATION);
        String prepare = sql.substring(sql.indexOf("create or replace function public.prepare_account_withdrawal"),
                sql.indexOf("create or replace function public.finalize_account_withdrawal"));

        assertThat(prepare.indexOf("perform wp.id from workplaces"))
                .isLessThan(prepare.indexOf("perform wk.id from workers"));
        assertThat(prepare.indexOf("perform wk.id from workers"))
                .isLessThan(prepare.indexOf("perform wl.id from worklogs"));
        assertThat(prepare.indexOf("perform wl.id from worklogs"))
                .isLessThan(prepare.indexOf("insert into account_withdrawal_jobs"));
    }

    @Test
    void 재가입연결은completedJob을새pending주기로초기화한다() throws Exception {
        String sql = Files.readString(MIGRATION);

        assertThat(sql).contains("connected=true", "v_status='completed' and not v_new_cycle",
                "status='pending', toss_disconnected=false", "completed_at=null");
    }

    @Test
    void 모든근무쓰기Rpc는검증된actor를받는다() throws Exception {
        String sql = Files.readString(MIGRATION);

        assertThat(sql).contains(
                "create_worker_by_owner(p_actor_user_key bigint",
                "join_worker_by_toss(\n  p_actor_user_key bigint",
                "patch_active_worker(\n  p_actor_user_key bigint",
                "create_active_worklog(p_actor_user_key bigint",
                "transition_active_worklog(\n  p_actor_user_key bigint");
    }
}
