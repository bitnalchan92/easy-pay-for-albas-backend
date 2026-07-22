-- 알바생 관계 종료, 근무별 지급 연결, 사업장 보관.
-- 기존 데이터의 시급은 현재 worker 시급으로 계산 기준만 backfill하며 payout_id는 절대 추정하지 않는다.

alter table workers drop constraint if exists workers_status_check;
alter table workers add constraint workers_status_check
  check (status in ('pending', 'approved', 'departed'));
alter table workers add column if not exists departed_at timestamptz;
alter table workers add column if not exists departure_reason text
  check (departure_reason in ('owner_removed', 'worker_left'));

alter table workplaces add column if not exists archived_at timestamptz;
alter table worklogs add column if not exists payout_id uuid references payouts(id);
alter table worklogs add column if not exists hourly_wage_snapshot integer;

update worklogs wl
set hourly_wage_snapshot = wk.hourly_wage
from workers wk
where wl.worker_id = wk.id and wl.hourly_wage_snapshot is null;

create unique index if not exists workers_active_toss_membership_idx
  on workers(workplace_id, toss_user_key)
  where toss_user_key is not null and status in ('pending', 'approved');

-- 범용 REST 쓰기도 종료/보관/pending withdrawal 장벽을 우회하지 못하게 DB에서 막는다.
create or replace function public.guard_employment_write() returns trigger
language plpgsql set search_path = public as $$
declare v_worker workers%rowtype; v_workplace workplaces%rowtype;
begin
  if tg_table_name = 'workers' then
    select * into v_workplace from workplaces where id = new.workplace_id for update;
    if tg_op = 'UPDATE' and old.status = 'departed' then
      if (new.status, new.name, new.hourly_wage, new.payday, new.departed_at, new.departure_reason)
          is distinct from
            (old.status, old.name, old.hourly_wage, old.payday, old.departed_at, old.departure_reason) then
        raise exception 'departed worker is read only';
      end if;
      if new.toss_user_key is null and new.phone = '' and new.bank is null and new.account is null then
        return new; -- 회원 탈퇴의 식별/연락/금융정보 제거만 허용.
      end if;
      raise exception 'departed worker is read only';
    end if;
  else
    select * into v_worker from workers where id = new.worker_id for update;
    select * into v_workplace from workplaces where id = v_worker.workplace_id for update;
    if v_worker.status <> 'approved' then raise exception 'worker is not active'; end if;
  end if;
  if v_workplace.archived_at is not null then raise exception 'workplace is archived'; end if;
  if exists (select 1 from account_withdrawal_jobs j
             where j.user_key = v_workplace.owner_toss_user_key and j.status = 'pending') then
    raise exception 'owner withdrawal is pending';
  end if;
  return new;
end;
$$;

drop trigger if exists workers_employment_write_guard on workers;
create trigger workers_employment_write_guard before insert or update on workers
for each row execute function guard_employment_write();
drop trigger if exists worklogs_employment_write_guard on worklogs;
create trigger worklogs_employment_write_guard before insert or update on worklogs
for each row execute function guard_employment_write();
drop trigger if exists payouts_employment_write_guard on payouts;
create trigger payouts_employment_write_guard before insert or update on payouts
for each row execute function guard_employment_write();

create or replace function public.depart_worker(
  p_actor_user_key bigint, p_worker_id uuid
) returns jsonb
language plpgsql security definer set search_path = public as $$
declare
  v_worker workers%rowtype;
  v_workplace workplaces%rowtype;
  v_reason text;
  v_blockers jsonb := '[]'::jsonb;
begin
  perform pg_advisory_xact_lock(p_actor_user_key);
  select * into v_worker from workers where id = p_worker_id;
  if not found then return jsonb_build_object('found', false); end if;
  select * into v_workplace from workplaces where id = v_worker.workplace_id for update;
  select * into v_worker from workers where id = p_worker_id for update;

  if v_workplace.owner_toss_user_key = p_actor_user_key then
    v_reason := 'owner_removed';
  elsif v_worker.toss_user_key = p_actor_user_key then
    v_reason := 'worker_left';
  else
    return jsonb_build_object('found', true, 'authorized', false);
  end if;

  if v_worker.status = 'departed' then
    return jsonb_build_object('found', true, 'authorized', true, 'allowed', true,
      'reason', v_worker.departure_reason);
  end if;

  perform id from worklogs where worker_id = p_worker_id for update;
  if exists (select 1 from worklogs where worker_id = p_worker_id and status = 'submitted') then
    v_blockers := v_blockers || '"UNRESOLVED_WORKLOGS"'::jsonb;
  end if;
  if exists (select 1 from worklogs where worker_id = p_worker_id and status = 'approved' and payout_id is null) then
    v_blockers := v_blockers || '"UNPAID_APPROVED_WORKLOGS"'::jsonb;
  end if;
  if jsonb_array_length(v_blockers) > 0 then
    return jsonb_build_object('found', true, 'authorized', true, 'allowed', false, 'blockers', v_blockers);
  end if;

  update workers set status = 'departed', departed_at = now(), departure_reason = v_reason
  where id = p_worker_id and status <> 'departed';
  return jsonb_build_object('found', true, 'authorized', true, 'allowed', true, 'reason', v_reason);
end;
$$;

create or replace function public.pay_worker_period(
  p_actor_user_key bigint, p_worker_id uuid, p_period text
) returns jsonb
language plpgsql security definer set search_path = public as $$
declare
  v_worker workers%rowtype;
  v_workplace workplaces%rowtype;
  v_payout payouts%rowtype;
  v_amount integer;
begin
  if p_period !~ '^\d{4}-\d{2}$' then raise exception 'invalid period'; end if;
  perform pg_advisory_xact_lock(p_actor_user_key);
  select * into v_worker from workers where id = p_worker_id;
  if not found then return jsonb_build_object('found', false); end if;
  select * into v_workplace from workplaces where id = v_worker.workplace_id for update;
  select * into v_worker from workers where id = p_worker_id for update;
  if v_workplace.owner_toss_user_key is distinct from p_actor_user_key then
    return jsonb_build_object('found', true, 'authorized', false);
  end if;
  if v_worker.status <> 'approved' or v_workplace.archived_at is not null then
    return jsonb_build_object('found', true, 'authorized', true, 'allowed', false);
  end if;
  if exists (select 1 from account_withdrawal_jobs j
             where j.user_key = p_actor_user_key and j.status = 'pending') then
    return jsonb_build_object('found', true, 'authorized', true, 'allowed', false);
  end if;

  perform id from worklogs
  where worker_id = p_worker_id and status = 'approved' and payout_id is null
    and to_char(date, 'YYYY-MM') = p_period
  for update;

  select coalesce(sum(round(
    extract(epoch from ((coalesce(end_date, date)::timestamp + end_time)
      - (date::timestamp + start_time))) / 3600
    * coalesce(hourly_wage_snapshot, v_worker.hourly_wage)
  )), 0)::integer into v_amount
  from worklogs
  where worker_id = p_worker_id and status = 'approved' and payout_id is null
    and to_char(date, 'YYYY-MM') = p_period;

  if v_amount <= 0 then return jsonb_build_object('found', true, 'authorized', true, 'allowed', true); end if;
  insert into payouts(worker_id, workplace_id, amount, period)
  values (p_worker_id, v_worker.workplace_id, v_amount, p_period) returning * into v_payout;
  update worklogs set payout_id = v_payout.id
  where worker_id = p_worker_id and status = 'approved' and payout_id is null
    and to_char(date, 'YYYY-MM') = p_period;
  return jsonb_build_object('found', true, 'authorized', true, 'allowed', true,
    'payout', jsonb_build_object(
      'id',v_payout.id,'worker_id',v_payout.worker_id,'workplace_id',v_payout.workplace_id,
      'amount',v_payout.amount,'period',v_payout.period,'paid_at',v_payout.paid_at));
end;
$$;

create or replace function public.create_worker_by_owner(p_actor_user_key bigint, p_body jsonb)
returns jsonb language plpgsql security definer set search_path = public as $$
declare v_workplace workplaces%rowtype; v_worker workers%rowtype;
begin
  perform pg_advisory_xact_lock(p_actor_user_key);
  select * into v_workplace from workplaces where id=(p_body->>'workplace_id')::uuid for update;
  if not found then return jsonb_build_object('found',false); end if;
  if v_workplace.owner_toss_user_key is distinct from p_actor_user_key then
    return jsonb_build_object('found',true,'authorized',false);
  end if;
  if v_workplace.archived_at is not null or exists (select 1 from account_withdrawal_jobs
      where user_key=p_actor_user_key and status='pending') then
    raise exception 'workplace is not writable';
  end if;
  insert into workers(workplace_id,device_id,toss_user_key,name,phone,hourly_wage,payday,status,bank,account)
  values(v_workplace.id,null,null,p_body->>'name',coalesce(p_body->>'phone',''),
    (p_body->>'hourly_wage')::integer,(p_body->>'payday')::integer,'approved',
    p_body->>'bank',p_body->>'account') returning * into v_worker;
  return jsonb_build_object(
    'id',v_worker.id,'workplace_id',v_worker.workplace_id,'name',v_worker.name,
    'phone',v_worker.phone,'hourly_wage',v_worker.hourly_wage,'payday',v_worker.payday,
    'status',v_worker.status,'bank',v_worker.bank,'account',v_worker.account,
    'departed_at',v_worker.departed_at,'departure_reason',v_worker.departure_reason,
    'is_self',false,'authorized',true);
end;
$$;

create or replace function public.join_worker_by_toss(
  p_actor_user_key bigint, p_invite_code text, p_name text
) returns jsonb language plpgsql security definer set search_path = public as $$
declare v_workplace workplaces%rowtype; v_worker workers%rowtype;
begin
  perform pg_advisory_xact_lock(p_actor_user_key);
  select * into v_workplace from workplaces
  where invite_code=upper(p_invite_code) and archived_at is null for update;
  if not found then return jsonb_build_object('found',false); end if;
  if exists (select 1 from account_withdrawal_jobs
      where user_key in (p_actor_user_key,v_workplace.owner_toss_user_key) and status='pending') then
    raise exception 'workplace is not writable';
  end if;
  select * into v_worker from workers where workplace_id=v_workplace.id
    and toss_user_key=p_actor_user_key and status in ('pending','approved') for update;
  if not found then
    insert into workers(workplace_id,device_id,toss_user_key,name,phone,hourly_wage,payday,status)
    values(v_workplace.id,null,p_actor_user_key,p_name,'',v_workplace.hourly_wage_default,
      v_workplace.payday,'pending') returning * into v_worker;
  end if;
  return jsonb_build_object('found',true,
    'worker',jsonb_build_object(
      'id',v_worker.id,'workplace_id',v_worker.workplace_id,'name',v_worker.name,
      'phone',v_worker.phone,'hourly_wage',v_worker.hourly_wage,'payday',v_worker.payday,
      'status',v_worker.status,'bank',v_worker.bank,'account',v_worker.account,
      'departed_at',v_worker.departed_at,'departure_reason',v_worker.departure_reason,
      'is_self',true),
    'workplace',jsonb_build_object(
      'id',v_workplace.id,'name',v_workplace.name,'address',v_workplace.address,
      'address_detail',v_workplace.address_detail,'hourly_wage_default',v_workplace.hourly_wage_default,
      'payday',v_workplace.payday,'archived_at',v_workplace.archived_at,'is_owner',false));
end;
$$;

create or replace function public.patch_active_worker(
  p_actor_user_key bigint, p_worker_id uuid, p_patch jsonb
) returns jsonb language plpgsql security definer set search_path = public as $$
declare v_worker workers%rowtype; v_workplace workplaces%rowtype; v_allowed boolean;
begin
  perform pg_advisory_xact_lock(p_actor_user_key);
  select * into v_worker from workers where id=p_worker_id;
  if not found then return jsonb_build_object('found',false); end if;
  select * into v_workplace from workplaces where id=v_worker.workplace_id for update;
  select * into v_worker from workers where id=p_worker_id and status in ('pending','approved') for update;
  if not found or v_workplace.archived_at is not null then raise exception 'worker is not writable'; end if;
  if exists (select 1 from account_withdrawal_jobs
      where user_key in (p_actor_user_key,v_workplace.owner_toss_user_key) and status='pending') then
    raise exception 'worker is not writable';
  end if;

  v_allowed := coalesce((v_workplace.owner_toss_user_key=p_actor_user_key
      and p_patch - array['name','hourly_wage','payday','status'] = '{}'::jsonb
      and (not p_patch ? 'status' or p_patch->>'status'='approved'))
    or (v_worker.toss_user_key=p_actor_user_key
      and p_patch - array['phone','bank','account'] = '{}'::jsonb),false);
  if not v_allowed then return jsonb_build_object('found',true,'authorized',false); end if;
  update workers set
    name = coalesce(p_patch->>'name', name), phone = coalesce(p_patch->>'phone', phone),
    hourly_wage = coalesce((p_patch->>'hourly_wage')::integer, hourly_wage),
    payday = coalesce((p_patch->>'payday')::integer, payday),
    bank = case when p_patch ? 'bank' then p_patch->>'bank' else bank end,
    account = case when p_patch ? 'account' then p_patch->>'account' else account end,
    status = coalesce(p_patch->>'status', status)
  where id = p_worker_id;
  return jsonb_build_object('found',true,'authorized',true,'ok',true);
end;
$$;

create or replace function public.create_active_worklog(p_actor_user_key bigint, p_body jsonb)
returns jsonb language plpgsql security definer set search_path = public as $$
declare v_worker workers%rowtype; v_workplace workplaces%rowtype; v_row worklogs%rowtype;
begin
  perform pg_advisory_xact_lock(p_actor_user_key);
  select * into v_worker from workers where id=(p_body->>'worker_id')::uuid;
  if not found then return jsonb_build_object('found',false); end if;
  select * into v_workplace from workplaces where id=v_worker.workplace_id for update;
  select * into v_worker from workers where id=v_worker.id and status='approved' for update;
  if not found or v_workplace.archived_at is not null then raise exception 'worker is not writable'; end if;
  if v_worker.toss_user_key is distinct from p_actor_user_key then
    return jsonb_build_object('found',true,'authorized',false);
  end if;
  if exists (select 1 from account_withdrawal_jobs
      where user_key in (p_actor_user_key,v_workplace.owner_toss_user_key) and status='pending') then
    raise exception 'worker is not writable';
  end if;
  insert into worklogs(worker_id, workplace_id, date, end_date, start_time, end_time, memo,
    status, hourly_wage_snapshot)
  values (v_worker.id, v_worker.workplace_id, (p_body->>'date')::date,
    coalesce((p_body->>'end_date')::date, (p_body->>'date')::date),
    (p_body->>'start_time')::time, (p_body->>'end_time')::time,
    p_body->>'memo', 'submitted', v_worker.hourly_wage) returning * into v_row;
  return jsonb_build_object(
    'id',v_row.id,'worker_id',v_row.worker_id,'workplace_id',v_row.workplace_id,
    'date',v_row.date,'end_date',v_row.end_date,'start_time',v_row.start_time,
    'end_time',v_row.end_time,'memo',v_row.memo,'rejection_reason',v_row.rejection_reason,
    'status',v_row.status,'payout_id',v_row.payout_id,
    'hourly_wage_snapshot',v_row.hourly_wage_snapshot,'authorized',true);
end;
$$;

create or replace function public.transition_active_worklog(
  p_actor_user_key bigint, p_worklog_id uuid, p_status text, p_rejection_reason text default null
) returns jsonb language plpgsql security definer set search_path = public as $$
declare v_row worklogs%rowtype; v_worker workers%rowtype; v_workplace workplaces%rowtype;
begin
  if p_status not in ('approved','rejected') then raise exception 'invalid transition'; end if;
  perform pg_advisory_xact_lock(p_actor_user_key);
  select * into v_row from worklogs where id=p_worklog_id;
  if not found then return jsonb_build_object('found',false); end if;
  select * into v_workplace from workplaces where id=v_row.workplace_id for update;
  select * into v_worker from workers where id=v_row.worker_id for update;
  select * into v_row from worklogs where id=p_worklog_id and status='submitted'
    and payout_id is null for update;
  if not found or v_worker.status <> 'approved' or v_workplace.archived_at is not null then
    raise exception 'worklog is not writable';
  end if;
  if v_workplace.owner_toss_user_key is distinct from p_actor_user_key then
    return jsonb_build_object('found',true,'authorized',false);
  end if;
  if exists (select 1 from account_withdrawal_jobs
      where user_key in (p_actor_user_key,v_workplace.owner_toss_user_key) and status='pending') then
    raise exception 'worklog is not writable';
  end if;
  update worklogs set status = p_status,
    rejection_reason = case when p_status = 'rejected' then p_rejection_reason else null end
  where id = p_worklog_id;
  return jsonb_build_object('found',true,'authorized',true,'ok',true);
end;
$$;

-- 회원 탈퇴: active 관계/미처리/미지급만 차단하고 소유 사업장을 archive한다.
create or replace function public.prepare_account_withdrawal(p_user_key bigint)
returns jsonb language plpgsql security definer set search_path = public as $$
declare
  v_blockers jsonb := '[]'::jsonb;
  v_wp record;
  v_status text;
  v_disconnected boolean;
  v_new_cycle boolean;
begin
  perform pg_advisory_xact_lock(p_user_key);
  select status, toss_disconnected into v_status, v_disconnected
  from account_withdrawal_jobs where user_key = p_user_key for update;

  v_new_cycle := exists (select 1 from toss_login_users where user_key=p_user_key and connected=true)
    or exists (select 1 from toss_login_tokens where user_key=p_user_key)
    or exists (select 1 from workplaces where owner_toss_user_key=p_user_key and archived_at is null)
    or exists (select 1 from workers where toss_user_key=p_user_key and status in ('pending','approved'));
  if v_status='completed' and not v_new_cycle then
    return jsonb_build_object('allowed',true,'blockers','[]'::jsonb,'tossDisconnected',true);
  end if;

  -- 모든 employment write RPC와 같은 workplace -> worker -> worklog 순서로 잠근다.
  -- 선행 write가 먼저 workplace lock을 잡았다면 여기서 기다린 뒤 최신 commit을 재검사한다.
  perform wp.id from workplaces wp
  where wp.owner_toss_user_key=p_user_key or wp.id in (
    select wk.workplace_id from workers wk
    where wk.toss_user_key=p_user_key and wk.status in ('pending','approved'))
  order by wp.id for update;
  perform wk.id from workers wk where wk.workplace_id in (
    select wp.id from workplaces wp
    where wp.owner_toss_user_key=p_user_key or wp.id in (
      select own.workplace_id from workers own
      where own.toss_user_key=p_user_key and own.status in ('pending','approved')))
  order by wk.id for update;
  perform wl.id from worklogs wl where wl.workplace_id in (
    select wp.id from workplaces wp
    where wp.owner_toss_user_key=p_user_key or wp.id in (
      select own.workplace_id from workers own
      where own.toss_user_key=p_user_key and own.status in ('pending','approved')))
  order by wl.id for update;

  for v_wp in select id from workplaces where owner_toss_user_key = p_user_key and archived_at is null loop
    if exists (select 1 from workers where workplace_id = v_wp.id and status in ('pending','approved')) then
      v_blockers := v_blockers || jsonb_build_object('code','ACTIVE_WORKERS','workplaceId',v_wp.id::text);
    end if;
    if exists (select 1 from worklogs where workplace_id = v_wp.id and status = 'submitted') then
      v_blockers := v_blockers || jsonb_build_object('code','UNRESOLVED_WORKLOGS','workplaceId',v_wp.id::text);
    end if;
    if exists (select 1 from worklogs where workplace_id = v_wp.id and status = 'approved' and payout_id is null) then
      v_blockers := v_blockers || jsonb_build_object('code','UNPAID_APPROVED_WORKLOGS','workplaceId',v_wp.id::text);
    end if;
  end loop;
  if exists (select 1 from workers where toss_user_key = p_user_key and status in ('pending','approved')) then
    v_blockers := v_blockers || jsonb_build_object('code','ACTIVE_WORKER_RELATION');
  end if;
  if jsonb_array_length(v_blockers) > 0 then
    return jsonb_build_object('allowed', false, 'blockers', v_blockers);
  end if;
  insert into account_withdrawal_jobs(user_key,status,toss_disconnected)
  values(p_user_key,'pending',false) on conflict(user_key) do update
  set status='pending', toss_disconnected=false, last_error=null, updated_at=now(), completed_at=null
  returning toss_disconnected into v_disconnected;
  return jsonb_build_object('allowed',true,'blockers','[]'::jsonb,'tossDisconnected',v_disconnected);
end;
$$;

create or replace function public.finalize_account_withdrawal(p_user_key bigint)
returns jsonb language plpgsql security definer set search_path = public as $$
declare v_status text; v_disconnected boolean;
begin
  perform pg_advisory_xact_lock(p_user_key);
  select status,toss_disconnected into v_status,v_disconnected from account_withdrawal_jobs
  where user_key=p_user_key for update;
  if v_status is null then raise exception 'withdrawal job not found'; end if;
  if v_status='completed' then return jsonb_build_object('ok',true); end if;
  if not v_disconnected then raise exception 'toss disconnect is not confirmed'; end if;
  perform wp.id from workplaces wp where wp.owner_toss_user_key=p_user_key or wp.id in
    (select wk.workplace_id from workers wk where wk.toss_user_key=p_user_key)
    order by wp.id for update;
  perform wk.id from workers wk where wk.toss_user_key=p_user_key or workplace_id in
    (select id from workplaces where owner_toss_user_key=p_user_key)
    order by wk.id for update;
  perform wl.id from worklogs wl where worker_id in (select id from workers where toss_user_key=p_user_key)
    or workplace_id in (select id from workplaces where owner_toss_user_key=p_user_key)
    order by wl.id for update;
  if exists (select 1 from workers wk join workplaces wp on wp.id=wk.workplace_id
      where wp.owner_toss_user_key=p_user_key and wk.status in ('pending','approved'))
    or exists (select 1 from worklogs wl join workplaces wp on wp.id=wl.workplace_id
      where wp.owner_toss_user_key=p_user_key and (wl.status='submitted' or (wl.status='approved' and wl.payout_id is null)))
    or exists (select 1 from workers where toss_user_key=p_user_key and status in ('pending','approved')) then
    raise exception 'withdrawal no longer eligible';
  end if;
  update workers set toss_user_key=null, phone='', bank=null, account=null where toss_user_key=p_user_key;
  update workplaces set archived_at=coalesce(archived_at,now()), owner_toss_user_key=null,
    owner_device_id=null, password_hash=null, business_number=null,
    invite_code='archived-' || id::text
  where owner_toss_user_key=p_user_key;
  delete from toss_login_tokens where user_key=p_user_key;
  update toss_login_users set scopes='{}'::text[], agreed_terms='{}'::text[], email=null,
    name=null, phone=null, birthday=null, gender=null, nationality=null, ci_encrypted=null, ci_hash=null,
    connected=false, disconnected_at=now(), updated_at=now() where user_key=p_user_key;
  update toss_login_callback_events set payload=jsonb_build_object('user_key',p_user_key,'referrer',referrer)
    where user_key=p_user_key;
  insert into account_withdrawal_tombstones(user_key) values(p_user_key) on conflict(user_key) do nothing;
  update account_withdrawal_jobs set status='completed',last_error=null,completed_at=now(),updated_at=now()
    where user_key=p_user_key;
  return jsonb_build_object('ok',true);
end;
$$;

revoke all on function public.depart_worker(bigint,uuid) from public,anon,authenticated;
revoke all on function public.pay_worker_period(bigint,uuid,text) from public,anon,authenticated;
revoke all on function public.create_worker_by_owner(bigint,jsonb) from public,anon,authenticated;
revoke all on function public.join_worker_by_toss(bigint,text,text) from public,anon,authenticated;
revoke all on function public.patch_active_worker(bigint,uuid,jsonb) from public,anon,authenticated;
revoke all on function public.create_active_worklog(bigint,jsonb) from public,anon,authenticated;
revoke all on function public.transition_active_worklog(bigint,uuid,text,text) from public,anon,authenticated;
grant execute on function public.depart_worker(bigint,uuid) to service_role;
grant execute on function public.pay_worker_period(bigint,uuid,text) to service_role;
grant execute on function public.create_worker_by_owner(bigint,jsonb) to service_role;
grant execute on function public.join_worker_by_toss(bigint,text,text) to service_role;
grant execute on function public.patch_active_worker(bigint,uuid,jsonb) to service_role;
grant execute on function public.create_active_worklog(bigint,jsonb) to service_role;
grant execute on function public.transition_active_worklog(bigint,uuid,text,text) to service_role;
