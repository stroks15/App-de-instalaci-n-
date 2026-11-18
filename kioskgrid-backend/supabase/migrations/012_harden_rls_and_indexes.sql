-- Harden RLS policy evaluation and remove redundant permissive policies.
-- This migration records the live security/performance changes applied to the project.

begin;

alter function public.grant_permanent_access(text) set search_path = pg_catalog, public;
alter function public.has_permanent_access(text) set search_path = pg_catalog, public;
revoke all on function public.grant_permanent_access(text) from public, anon, authenticated;
revoke all on function public.has_permanent_access(text) from public, anon, authenticated;
grant execute on function public.grant_permanent_access(text) to service_role;
grant execute on function public.has_permanent_access(text) to service_role;


drop policy if exists "Usuarios pueden ver sus propios dispositivos" on public.devices;
drop policy if exists "Usuarios pueden ver tareas de sus dispositivos" on public.tasks;

alter policy "Usuarios pueden insertar dispositivos" on public.devices
  to authenticated
  with check ((select auth.uid()) = owner_id);

alter policy "Usuarios pueden crear tareas" on public.tasks
  to authenticated
  with check (exists (
    select 1
    from public.devices d
    where d.id = tasks.device_id
      and d.owner_id = (select auth.uid())
  ));

alter policy "Usuarios pueden editar sus configs" on public.kiosk_configs
  to authenticated
  using (exists (
    select 1
    from public.devices d
    where d.id = kiosk_configs.device_id
      and d.owner_id = (select auth.uid())
  ));

alter policy "Usuarios pueden ver configs de sus dispositivos" on public.kiosk_configs
  to authenticated
  using (exists (
    select 1
    from public.devices d
    where d.id = kiosk_configs.device_id
      and d.owner_id = (select auth.uid())
  ));

alter policy "Users can read own data" on public.users
  to authenticated
  using ((select auth.uid()) = id or (select auth.email()) = email);

alter policy "Users can update own data" on public.users
  to authenticated
  using ((select auth.uid()) = id or (select auth.email()) = email);

drop policy if exists "Allow authenticated read all coupons" on public.coupons;
drop policy if exists "Allow service role full access" on public.coupons;
drop policy if exists "Service role full access users" on public.users;

create index if not exists device_audit_logs_actor_id_idx
  on public.device_audit_logs(actor_id);
create index if not exists device_audit_logs_owner_id_idx
  on public.device_audit_logs(owner_id);
create index if not exists kiosk_configs_device_id_idx
  on public.kiosk_configs(device_id);

drop index if exists public.idx_devices_owner;

commit;
