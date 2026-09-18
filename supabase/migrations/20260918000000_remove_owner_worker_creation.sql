revoke execute on function public.create_worker_by_owner(bigint,jsonb) from service_role;
drop function if exists public.create_worker_by_owner(bigint,jsonb);
