-- Make ownership invariants explicit for UPDATE policies.
-- PostgreSQL reuses USING as the implicit WITH CHECK when omitted; keeping
-- both clauses explicit makes the intended row-ownership boundary auditable.

begin;

alter policy "Usuarios pueden editar sus configs"
on public.kiosk_configs
with check (
  exists (
    select 1
    from public.devices d
    where d.id = kiosk_configs.device_id
      and d.owner_id = (select auth.uid())
  )
);

alter policy "Users can update own data"
on public.users
with check (
  (select auth.uid()) = id
  or (select auth.email()) = email
);

commit;
