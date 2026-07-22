package com.albapay.backend.data;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class DataProjectionMigrationTest {
    private static final Path MIGRATION = Path.of(
            "supabase/migrations/20260722030000_backend_session_access.sql");

    @Test
    void data응답은전체행이나로그인식별자를반환하지않는다() throws Exception {
        String function = getActorDataFunction();

        assertThat(function).doesNotContain("to_jsonb(", "'password_hash'", "'owner_toss_user_key'",
                "'owner_device_id'", "'toss_user_key'", "'device_id'");
        assertThat(function).contains("'is_owner'", "'is_self'");
    }

    @Test
    void 사업자번호와초대코드는소유사업장분기에만있다() throws Exception {
        String function = getActorDataFunction();
        String ownerOnly = function.substring(function.indexOf("|| case when"),
                function.indexOf("else '{}'::jsonb end"));

        assertThat(ownerOnly).contains("wp.owner_toss_user_key=p_actor_user_key",
                "'business_number'", "'invite_code'");
    }

    @Test
    void 근무일지와지급내역은명시필드만투영한다() throws Exception {
        String function = getActorDataFunction();

        assertThat(function).contains("'hourly_wage_snapshot',wl.hourly_wage_snapshot",
                "'paid_at',po.paid_at");
    }

    @Test
    void 클라이언트응답Rpc는전체행을직렬화하지않는다() throws Exception {
        String employment = Files.readString(Path.of(
                "supabase/migrations/20260722020000_worker_departure_and_archival.sql"));
        String session = Files.readString(MIGRATION);

        assertThat(employment).doesNotContain("to_jsonb(");
        assertThat(session).doesNotContain("to_jsonb(");
    }

    private static String getActorDataFunction() throws Exception {
        String sql = Files.readString(MIGRATION);
        return sql.substring(sql.indexOf("create or replace function public.get_actor_data"),
                sql.indexOf("create or replace function public.create_workplace_for_actor"));
    }
}
