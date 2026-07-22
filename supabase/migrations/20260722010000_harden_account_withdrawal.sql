-- 이미 적용된 회원 탈퇴 migration을 안전하게 보완한다.
-- 1) Toss 연결 해제가 확인되지 않은 job은 finalize 금지.
-- 2) FK 부모 row를 FOR UPDATE로 잠가 동시 child insert와 cascade delete가 경쟁하지 못하게 한다.

create or replace function public.mark_account_withdrawal_toss_disconnected(p_user_key bigint)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
  v_marked boolean;
begin
  update account_withdrawal_jobs
  set toss_disconnected = true, last_error = null, updated_at = now()
  where user_key = p_user_key and status = 'pending';
  v_marked := found;
  return jsonb_build_object('marked', v_marked);
end;
$$;

create or replace function public.finalize_account_withdrawal(p_user_key bigint)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
  v_status text;
  v_toss_disconnected boolean;
begin
  perform pg_advisory_xact_lock(p_user_key);

  select status, toss_disconnected into v_status, v_toss_disconnected
  from account_withdrawal_jobs
  where user_key = p_user_key
  for update;

  if v_status is null then
    raise exception 'withdrawal job not found';
  end if;
  if v_status = 'completed' then
    return jsonb_build_object('ok', true);
  end if;
  if not v_toss_disconnected then
    raise exception 'toss disconnect is not confirmed';
  end if;

  -- FK insert는 참조 부모에 KEY SHARE를 잡는다. FOR UPDATE는 그 lock과 충돌하므로,
  -- 아래 잠금 뒤 시작된 worker/worklog/payout insert는 finalize가 끝날 때까지 진행하지 못한다.
  perform w.id
  from workplaces w
  where w.owner_toss_user_key = p_user_key
  for update;

  perform wk.id
  from workers wk
  where wk.toss_user_key = p_user_key
  for update;

  -- 잠금 대기 전에 시작된 insert까지 보이도록 lock 획득 뒤 eligibility를 다시 검사한다.
  if exists (
    select 1 from workplaces w
    where w.owner_toss_user_key = p_user_key
      and (exists (select 1 from workers  wk where wk.workplace_id = w.id)
        or exists (select 1 from worklogs wl where wl.workplace_id = w.id)
        or exists (select 1 from payouts  p  where p.workplace_id  = w.id))
  ) then
    raise exception 'withdrawal no longer eligible: owned workplace not empty';
  end if;

  delete from workplaces w
  where w.owner_toss_user_key = p_user_key
    and not exists (select 1 from workers  wk where wk.workplace_id = w.id)
    and not exists (select 1 from worklogs wl where wl.workplace_id = w.id)
    and not exists (select 1 from payouts  p  where p.workplace_id  = w.id);

  delete from workers wk
  where wk.toss_user_key = p_user_key
    and wk.status = 'pending'
    and not exists (select 1 from worklogs wl where wl.worker_id = wk.id)
    and not exists (select 1 from payouts  p  where p.worker_id  = wk.id);

  update workers wk
  set toss_user_key = null, phone = '', bank = null, account = null
  where wk.toss_user_key = p_user_key;

  delete from toss_login_tokens where user_key = p_user_key;

  update toss_login_users
  set scopes = '{}'::text[], agreed_terms = '{}'::text[],
      email = null, name = null, phone = null, birthday = null, gender = null,
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
  set status = 'completed', last_error = null, completed_at = now(), updated_at = now()
  where user_key = p_user_key;

  return jsonb_build_object('ok', true);
end;
$$;

revoke all on function public.mark_account_withdrawal_toss_disconnected(bigint)
  from public, anon, authenticated;
revoke all on function public.finalize_account_withdrawal(bigint)
  from public, anon, authenticated;
grant execute on function public.mark_account_withdrawal_toss_disconnected(bigint) to service_role;
grant execute on function public.finalize_account_withdrawal(bigint) to service_role;

-- 운영 검증:
-- select finalize_account_withdrawal(<pending_false_user_key>); -- 반드시 실패, 데이터 불변
-- 세션 A가 finalize 중 부모 row를 잠근 동안 세션 B의 child insert는 대기해야 한다.
-- 세션 B가 먼저 commit한 경우 세션 A의 재검사가 실패하며 cascade 삭제하지 않아야 한다.
