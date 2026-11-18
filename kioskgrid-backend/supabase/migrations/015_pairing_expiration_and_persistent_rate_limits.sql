-- Pairing codes expire and failed pairing attempts are throttled persistently.
alter table public.devices
  add column if not exists pairing_expires_at timestamptz;

-- Give already-issued, unpaired codes a short migration grace period.
update public.devices
set pairing_expires_at = now() + interval '10 minutes'
where owner_id is null
  and pairing_code is not null
  and pairing_expires_at is null;

create schema if not exists private;

create table if not exists private.pairing_attempt_limits (
  scope_hash text primary key,
  window_started_at timestamptz not null,
  attempts integer not null default 0,
  blocked_until timestamptz
);

revoke all on schema private from public, anon, authenticated;
grant usage on schema private to service_role;
alter table private.pairing_attempt_limits enable row level security;
revoke all on table private.pairing_attempt_limits from public, anon, authenticated;
grant select, insert, update, delete on table private.pairing_attempt_limits to service_role;

create or replace function public.consume_pairing_attempt(
  p_scope_hash text,
  p_max_attempts integer default 5,
  p_window_seconds integer default 900,
  p_block_seconds integer default 900
)
returns table(allowed boolean, retry_after_seconds integer)
language plpgsql
security definer
set search_path = pg_catalog, private
as $$
declare
  v_row private.pairing_attempt_limits%rowtype;
  v_now timestamptz := clock_timestamp();
begin
  if p_scope_hash is null or length(p_scope_hash) <> 64
     or p_max_attempts < 1 or p_window_seconds < 1 or p_block_seconds < 1 then
    raise exception 'invalid rate limit parameters';
  end if;

  insert into private.pairing_attempt_limits(scope_hash, window_started_at, attempts)
  values (p_scope_hash, v_now, 0)
  on conflict (scope_hash) do nothing;

  select * into v_row
  from private.pairing_attempt_limits
  where scope_hash = p_scope_hash
  for update;

  if v_row.blocked_until is not null and v_row.blocked_until > v_now then
    return query select false, greatest(1, ceil(extract(epoch from (v_row.blocked_until - v_now)))::integer);
    return;
  end if;

  if v_row.window_started_at <= v_now - make_interval(secs => p_window_seconds)
     or (v_row.blocked_until is not null and v_row.blocked_until <= v_now) then
    update private.pairing_attempt_limits
      set window_started_at = v_now, attempts = 1, blocked_until = null
      where scope_hash = p_scope_hash;
    return query select true, 0;
    return;
  end if;

  if v_row.attempts >= p_max_attempts then
    update private.pairing_attempt_limits
      set blocked_until = v_now + make_interval(secs => p_block_seconds)
      where scope_hash = p_scope_hash;
    return query select false, p_block_seconds;
    return;
  end if;

  update private.pairing_attempt_limits
    set attempts = attempts + 1
    where scope_hash = p_scope_hash;
  return query select true, 0;
end;
$$;

revoke all on function public.consume_pairing_attempt(text, integer, integer, integer)
  from public, anon, authenticated;
grant execute on function public.consume_pairing_attempt(text, integer, integer, integer)
  to service_role;
