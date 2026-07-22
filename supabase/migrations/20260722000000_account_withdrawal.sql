-- 알바페이 회원 탈퇴: 상태 테이블 + 판정/실행 RPC.
-- Spring에서 여러 PostgREST 요청을 순서대로 호출해 transaction을 흉내내지 않는다. 공유 기록의 cascade
-- 삭제 위험 때문에 판정과 DB 변경을 이 함수들 안에서 transaction + advisory lock으로 처리한다.

-- ── 상태 테이블 ────────────────────────────────────────────────────────────────
create table if not exists public.account_withdrawal_jobs (
  user_key bigint primary key,
  status text not null default 'pending' check (status in ('pending', 'completed')),
  toss_disconnected boolean not null default false,
  last_error text,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  completed_at timestamptz
);

-- 재가입을 막는 용도가 아니라, user_key + withdrawn_at만 제한 보존한다. (보존 기간은 정책 확정 전 미하드코딩)
create table if not exists public.account_withdrawal_tombstones (
  user_key bigint primary key,
  withdrawn_at timestamptz not null default now()
);

-- 이 테이블들은 RPC(security definer) + service_role로만 접근한다. anon/authenticated는 정책 없음 → 거부.
alter table public.account_withdrawal_jobs enable row level security;
alter table public.account_withdrawal_tombstones enable row level security;

-- ── prepare: 판정 + job pending upsert ────────────────────────────────────────
-- 소유 사업장에 worker/worklog/payout이 하나라도(상태·기간·금액 무관) 있으면 전체 차단하고
-- job/개인정보/토큰을 건드리지 않는다.
create or replace function public.prepare_account_withdrawal(p_user_key bigint)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
  v_blockers jsonb := '[]'::jsonb;
  v_wp record;
  v_status text;
  v_toss_disconnected boolean;
begin
  perform pg_advisory_xact_lock(p_user_key);

  -- 완료된 동일 작업의 재처리는 finalize가 no-op이므로 그대로 허용해 200으로 수렴시킨다.
  select status, toss_disconnected into v_status, v_toss_disconnected
  from account_withdrawal_jobs where user_key = p_user_key;
  if v_status = 'completed' then
    return jsonb_build_object('allowed', true, 'blockers', '[]'::jsonb,
      'tossDisconnected', coalesce(v_toss_disconnected, true));
  end if;

  for v_wp in
    select id from workplaces where owner_toss_user_key = p_user_key
  loop
    if exists (select 1 from workers  where workplace_id = v_wp.id)
       or exists (select 1 from worklogs where workplace_id = v_wp.id)
       or exists (select 1 from payouts  where workplace_id = v_wp.id) then
      v_blockers := v_blockers || jsonb_build_object(
        'code', 'OWNED_WORKPLACE_NOT_EMPTY', 'workplaceId', v_wp.id::text);
    end if;
  end loop;

  if jsonb_array_length(v_blockers) > 0 then
    return jsonb_build_object('allowed', false, 'blockers', v_blockers);
  end if;

  insert into account_withdrawal_jobs (user_key, status)
  values (p_user_key, 'pending')
  on conflict (user_key) do update set status = 'pending', updated_at = now()
  returning toss_disconnected into v_toss_disconnected;

  return jsonb_build_object('allowed', true, 'blockers', '[]'::jsonb,
    'tossDisconnected', coalesce(v_toss_disconnected, false));
end;
$$;

-- ── finalize: 재검사 후 한 transaction에서 실제 삭제/익명화 ────────────────────
create or replace function public.finalize_account_withdrawal(p_user_key bigint)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
  v_status text;
begin
  perform pg_advisory_xact_lock(p_user_key);

  select status into v_status from account_withdrawal_jobs where user_key = p_user_key;
  if v_status is null then
    raise exception 'withdrawal job not found for user_key';
  end if;
  if v_status = 'completed' then
    return jsonb_build_object('ok', true); -- 멱등
  end if;

  -- eligibility 재검사: 소유 사업장에 새 worker/worklog/payout이 생겼으면 전체 rollback.
  if exists (
    select 1 from workplaces w
    where w.owner_toss_user_key = p_user_key
      and (exists (select 1 from workers  wk where wk.workplace_id = w.id)
        or exists (select 1 from worklogs wl where wl.workplace_id = w.id)
        or exists (select 1 from payouts  p  where p.workplace_id  = w.id))
  ) then
    raise exception 'withdrawal no longer eligible: owned workplace not empty';
  end if;

  -- 빈 소유 사업장만 삭제. FK cascade에 기대어 기록 있는 사업장을 지우지 않는다.
  delete from workplaces w
  where w.owner_toss_user_key = p_user_key
    and not exists (select 1 from workers  wk where wk.workplace_id = w.id)
    and not exists (select 1 from worklogs wl where wl.workplace_id = w.id)
    and not exists (select 1 from payouts  p  where p.workplace_id  = w.id);

  -- worker: 이력 없는 pending만 삭제(조건부). 이력 있는 worker와 approved는 아래 update로 익명화.
  delete from workers wk
  where wk.toss_user_key = p_user_key
    and wk.status = 'pending'
    and not exists (select 1 from worklogs wl where wl.worker_id = wk.id)
    and not exists (select 1 from payouts  p  where p.worker_id  = wk.id);

  -- 남은 해당 worker는 이름/기록은 보존하고 Toss·전화·계좌 연결만 제거한다. (phone은 NOT NULL이라 ''로)
  update workers wk
  set toss_user_key = null, phone = '', bank = null, account = null
  where wk.toss_user_key = p_user_key;

  -- 토큰 삭제 + 프로필 개인정보 제거 + callback raw payload를 허용 필드만으로 축소.
  delete from toss_login_tokens where user_key = p_user_key;

  update toss_login_users
  set email = null, name = null, phone = null, birthday = null, gender = null,
      nationality = null, ci_encrypted = null, ci_hash = null,
      connected = false, disconnected_at = now(), updated_at = now()
  where user_key = p_user_key;

  update toss_login_callback_events
  set payload = jsonb_build_object('user_key', p_user_key, 'referrer', referrer)
  where user_key = p_user_key;

  insert into account_withdrawal_tombstones (user_key)
  values (p_user_key)
  on conflict (user_key) do nothing;

  update account_withdrawal_jobs
  set status = 'completed', toss_disconnected = true, last_error = null,
      completed_at = now(), updated_at = now()
  where user_key = p_user_key;

  return jsonb_build_object('ok', true);
end;
$$;

-- ── 권한: service-role만 실행. public/anon/authenticated는 회수. ─────────────────
-- Supabase는 public 스키마 함수에 anon/authenticated EXECUTE를 기본 부여하므로 두 role에서도 직접 회수한다.
revoke all on function public.prepare_account_withdrawal(bigint)  from public, anon, authenticated;
revoke all on function public.finalize_account_withdrawal(bigint) from public, anon, authenticated;
grant execute on function public.prepare_account_withdrawal(bigint)  to service_role;
grant execute on function public.finalize_account_withdrawal(bigint) to service_role;
