-- 상태 변경과 같은 트랜잭션에서 내부용 푸시 컨텍스트를 반환한다.

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

  if v_amount <= 0 then return jsonb_build_object(
    'found', true, 'authorized', true, 'allowed', true
  ); end if;
  insert into payouts(worker_id, workplace_id, amount, period)
  values (p_worker_id, v_worker.workplace_id, v_amount, p_period) returning * into v_payout;
  update worklogs set payout_id = v_payout.id
  where worker_id = p_worker_id and status = 'approved' and payout_id is null
    and to_char(date, 'YYYY-MM') = p_period;
  return jsonb_build_object(
    'found', true, 'authorized', true, 'allowed', true,
    'payout', jsonb_build_object(
      'id',v_payout.id,'worker_id',v_payout.worker_id,'workplace_id',v_payout.workplace_id,
      'amount',v_payout.amount,'period',v_payout.period,'paid_at',v_payout.paid_at),
    'notification', jsonb_build_object(
      'event','payout_completed','recipientUserKey',v_worker.toss_user_key,
      'context',jsonb_build_object('period',v_payout.period,'amount',v_payout.amount)));
end;
$$;

create or replace function public.join_worker_by_toss(
  p_actor_user_key bigint, p_invite_code text, p_name text
) returns jsonb language plpgsql security definer set search_path = public as $$
declare
  v_workplace workplaces%rowtype;
  v_worker workers%rowtype;
  v_created boolean := false;
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
    v_created := true;
  end if;
  return jsonb_build_object(
    'found',true,
    'worker',jsonb_build_object(
      'id',v_worker.id,'workplace_id',v_worker.workplace_id,'name',v_worker.name,
      'phone',v_worker.phone,'hourly_wage',v_worker.hourly_wage,'payday',v_worker.payday,
      'status',v_worker.status,'bank',v_worker.bank,'account',v_worker.account,
      'departed_at',v_worker.departed_at,'departure_reason',v_worker.departure_reason,
      'is_self',true),
    'workplace',jsonb_build_object(
      'id',v_workplace.id,'name',v_workplace.name,'address',v_workplace.address,
      'address_detail',v_workplace.address_detail,'hourly_wage_default',v_workplace.hourly_wage_default,
      'payday',v_workplace.payday,'archived_at',v_workplace.archived_at,'is_owner',false)
  ) || case when v_created then jsonb_build_object(
    'notification',jsonb_build_object(
      'event','worker_joined','recipientUserKey',v_workplace.owner_toss_user_key,
      'context',jsonb_build_object('workerName',v_worker.name))
  ) else '{}'::jsonb end;
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
    'hourly_wage_snapshot',v_row.hourly_wage_snapshot,'authorized',true,
    'notification',jsonb_build_object(
      'event','worklog_submitted','recipientUserKey',v_workplace.owner_toss_user_key,
      'context',jsonb_build_object('workerName',v_worker.name,'workDate',v_row.date)));
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
  return jsonb_build_object(
    'found',true,'authorized',true,'ok',true,
    'notification',jsonb_build_object(
      'event','worklog_reviewed','recipientUserKey',v_worker.toss_user_key,
      'context',jsonb_build_object(
        'workDate',v_row.date,
        'reviewResult',case when p_status='approved' then '승인' else '반려' end)));
end;
$$;
