-- Toss 세션 actor 기반 backend만 핵심 데이터에 접근한다.

create or replace function public.get_actor_data(p_actor_user_key bigint)
returns jsonb language sql security definer set search_path = public as $$
  with actor_workers as (
    select id, workplace_id from workers where toss_user_key=p_actor_user_key
  ), visible_workplaces as (
    select id from workplaces where owner_toss_user_key=p_actor_user_key
    union select workplace_id from actor_workers
  ), visible_workers as (
    select wk.id from workers wk
    where wk.workplace_id in (select id from workplaces where owner_toss_user_key=p_actor_user_key)
       or wk.id in (select id from actor_workers)
  )
  select jsonb_build_object(
    'workplaces',coalesce((select jsonb_agg(
      jsonb_build_object(
        'id',wp.id,'name',wp.name,'address',wp.address,'address_detail',wp.address_detail,
        'hourly_wage_default',wp.hourly_wage_default,'payday',wp.payday,
        'archived_at',wp.archived_at,'is_owner',wp.owner_toss_user_key=p_actor_user_key
      ) || case when wp.owner_toss_user_key=p_actor_user_key then jsonb_build_object(
        'business_number',wp.business_number,'invite_code',wp.invite_code
      ) else '{}'::jsonb end order by wp.created_at)
      from workplaces wp where wp.id in (select id from visible_workplaces)),'[]'::jsonb),
    'workers',coalesce((select jsonb_agg(jsonb_build_object(
      'id',wk.id,'workplace_id',wk.workplace_id,'name',wk.name,'phone',wk.phone,
      'hourly_wage',wk.hourly_wage,'payday',wk.payday,'status',wk.status,
      'bank',wk.bank,'account',wk.account,'departed_at',wk.departed_at,
      'departure_reason',wk.departure_reason,'is_self',wk.toss_user_key=p_actor_user_key
    ) order by wk.created_at)
      from workers wk where wk.id in (select id from visible_workers)),'[]'::jsonb),
    'worklogs',coalesce((select jsonb_agg(jsonb_build_object(
      'id',wl.id,'worker_id',wl.worker_id,'workplace_id',wl.workplace_id,
      'date',wl.date,'end_date',wl.end_date,'start_time',wl.start_time,
      'end_time',wl.end_time,'memo',wl.memo,'rejection_reason',wl.rejection_reason,
      'status',wl.status,'payout_id',wl.payout_id,
      'hourly_wage_snapshot',wl.hourly_wage_snapshot
    ) order by wl.created_at)
      from worklogs wl where wl.worker_id in (select id from visible_workers)),'[]'::jsonb),
    'payouts',coalesce((select jsonb_agg(jsonb_build_object(
      'id',po.id,'worker_id',po.worker_id,'workplace_id',po.workplace_id,
      'amount',po.amount,'period',po.period,'paid_at',po.paid_at
    ) order by po.created_at)
      from payouts po where po.worker_id in (select id from visible_workers)),'[]'::jsonb)
  );
$$;

create or replace function public.create_workplace_for_actor(p_actor_user_key bigint,p_body jsonb)
returns jsonb language plpgsql security definer set search_path = public as $$
declare v_row workplaces%rowtype;
begin
  perform pg_advisory_xact_lock(p_actor_user_key);
  if exists (select 1 from account_withdrawal_jobs
      where user_key=p_actor_user_key and status='pending') then
    raise exception 'account withdrawal is pending';
  end if;
  insert into workplaces(name,address,address_detail,owner_device_id,owner_toss_user_key,
    invite_code,hourly_wage_default,payday,business_number)
  values(p_body->>'name',coalesce(p_body->>'address',''),p_body->>'address_detail',null,
    p_actor_user_key,p_body->>'invite_code',coalesce((p_body->>'hourly_wage_default')::integer,10030),
    coalesce((p_body->>'payday')::integer,25),p_body->>'business_number')
  returning * into v_row;
  return jsonb_build_object(
    'id',v_row.id,'name',v_row.name,'address',v_row.address,
    'address_detail',v_row.address_detail,'business_number',v_row.business_number,
    'invite_code',v_row.invite_code,'hourly_wage_default',v_row.hourly_wage_default,
    'payday',v_row.payday,'archived_at',v_row.archived_at,'is_owner',true);
end;
$$;

drop policy if exists "anon full access workplaces" on public.workplaces;
drop policy if exists "anon full access workers" on public.workers;
drop policy if exists "anon full access worklogs" on public.worklogs;
drop policy if exists "anon full access payouts" on public.payouts;

revoke all privileges on all tables in schema public from anon,authenticated;
revoke all privileges on all sequences in schema public from anon,authenticated;
revoke execute on all functions in schema public from public,anon,authenticated;
revoke create on schema public from anon,authenticated;
grant usage on schema public to service_role;
grant all privileges on all tables in schema public to service_role;
grant all privileges on all sequences in schema public to service_role;
grant execute on function public.get_actor_data(bigint) to service_role;
grant execute on function public.create_workplace_for_actor(bigint,jsonb) to service_role;

alter default privileges in schema public revoke all on tables from anon,authenticated;
alter default privileges in schema public revoke all on sequences from anon,authenticated;
alter default privileges in schema public revoke execute on functions from public,anon,authenticated;
alter default privileges in schema public grant all on tables to service_role;
alter default privileges in schema public grant all on sequences to service_role;
alter default privileges in schema public grant execute on functions to service_role;
