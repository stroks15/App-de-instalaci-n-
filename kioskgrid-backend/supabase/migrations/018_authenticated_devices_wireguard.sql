-- 016: authenticated device ownership and stable VPN addressing.
-- Legacy owner_id remains populated because existing Control endpoints use it.
BEGIN;

ALTER TABLE public.devices
  ADD COLUMN IF NOT EXISTS user_id uuid REFERENCES auth.users(id) ON DELETE CASCADE,
  ADD COLUMN IF NOT EXISTS device_name text,
  ADD COLUMN IF NOT EXISTS device_role text NOT NULL DEFAULT 'agent',
  ADD COLUMN IF NOT EXISTS public_key text,
  ADD COLUMN IF NOT EXISTS assigned_ip inet,
  ADD COLUMN IF NOT EXISTS vpn_enabled boolean NOT NULL DEFAULT false,
  ADD COLUMN IF NOT EXISTS vpn_status text NOT NULL DEFAULT 'not_configured';

UPDATE public.devices
SET user_id = COALESCE(user_id, owner_id),
    device_name = COALESCE(device_name, name)
WHERE user_id IS NULL OR device_name IS NULL;

CREATE SEQUENCE IF NOT EXISTS public.kioskgrid_vpn_ip_seq
  AS bigint MINVALUE 2 MAXVALUE 65534 START WITH 2 INCREMENT BY 1 NO CYCLE;

CREATE UNIQUE INDEX IF NOT EXISTS devices_assigned_ip_unique
  ON public.devices(assigned_ip) WHERE assigned_ip IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS devices_wireguard_public_key_unique
  ON public.devices(public_key) WHERE public_key IS NOT NULL;
CREATE INDEX IF NOT EXISTS devices_user_id_idx ON public.devices(user_id);

ALTER TABLE public.devices DROP CONSTRAINT IF EXISTS devices_device_role_check;
ALTER TABLE public.devices ADD CONSTRAINT devices_device_role_check
  CHECK (device_role IN ('agent', 'control'));

ALTER TABLE public.devices DROP CONSTRAINT IF EXISTS devices_assigned_ip_subnet_check;
ALTER TABLE public.devices ADD CONSTRAINT devices_assigned_ip_subnet_check
  CHECK (assigned_ip IS NULL OR assigned_ip <<= '10.88.0.0/16'::inet);

ALTER TABLE public.devices DROP CONSTRAINT IF EXISTS devices_vpn_status_check;
ALTER TABLE public.devices ADD CONSTRAINT devices_vpn_status_check
  CHECK (vpn_status IN ('not_configured', 'pending_gateway', 'active', 'error', 'disabled'));

CREATE OR REPLACE FUNCTION public.register_authenticated_device(
  p_user_id uuid,
  p_device_id text,
  p_device_name text,
  p_device_role text DEFAULT 'agent',
  p_public_key text DEFAULT NULL
)
RETURNS TABLE (
  id uuid,
  device_id text,
  device_name text,
  device_role text,
  assigned_ip inet,
  public_key text
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
  v_existing public.devices%ROWTYPE;
  v_ip inet;
BEGIN
  IF p_user_id IS NULL THEN RAISE EXCEPTION 'user_required'; END IF;
  IF p_device_id IS NULL OR p_device_id !~ '^[A-Za-z0-9._:-]{8,128}$' THEN
    RAISE EXCEPTION 'invalid_device_id';
  END IF;
  IF p_device_name IS NULL OR length(trim(p_device_name)) NOT BETWEEN 1 AND 80 THEN
    RAISE EXCEPTION 'invalid_device_name';
  END IF;
  IF p_device_role NOT IN ('agent', 'control') THEN RAISE EXCEPTION 'invalid_device_role'; END IF;
  IF p_public_key IS NOT NULL AND p_public_key !~ '^[A-Za-z0-9+/]{43}=$' THEN
    RAISE EXCEPTION 'invalid_wireguard_public_key';
  END IF;

  PERFORM pg_advisory_xact_lock(hashtextextended(p_device_id, 0));

  SELECT * INTO v_existing
  FROM public.devices d
  WHERE d.device_id = p_device_id
  FOR UPDATE;

  IF FOUND THEN
    IF COALESCE(v_existing.owner_id, v_existing.user_id) IS NOT NULL
       AND COALESCE(v_existing.owner_id, v_existing.user_id) <> p_user_id THEN
      RAISE EXCEPTION 'device_already_owned';
    END IF;
    v_ip := COALESCE(v_existing.assigned_ip,
      '10.88.0.0'::inet + nextval('public.kioskgrid_vpn_ip_seq'));

    UPDATE public.devices d SET
      owner_id = p_user_id,
      user_id = p_user_id,
      hardware_id = COALESCE(d.hardware_id, p_device_id),
      name = trim(p_device_name),
      device_name = trim(p_device_name),
      device_role = p_device_role,
      public_key = COALESCE(p_public_key, d.public_key),
      assigned_ip = v_ip,
      status = 'online',
      last_seen = now(),
      updated_at = now()
    WHERE d.id = v_existing.id
    RETURNING * INTO v_existing;
  ELSE
    v_ip := '10.88.0.0'::inet + nextval('public.kioskgrid_vpn_ip_seq');
    INSERT INTO public.devices (
      device_id, hardware_id, name, device_name, owner_id, user_id,
      device_role, public_key, assigned_ip, status, last_seen, updated_at
    ) VALUES (
      p_device_id, p_device_id, trim(p_device_name), trim(p_device_name),
      p_user_id, p_user_id, p_device_role, p_public_key, v_ip,
      'online', now(), now()
    ) RETURNING * INTO v_existing;
  END IF;

  RETURN QUERY SELECT v_existing.id, v_existing.device_id,
    v_existing.device_name, v_existing.device_role,
    v_existing.assigned_ip, v_existing.public_key;
END;
$$;

REVOKE ALL ON FUNCTION public.register_authenticated_device(uuid, text, text, text, text)
  FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.register_authenticated_device(uuid, text, text, text, text)
  TO service_role;

-- Existing owner_id-based RLS policies remain compatible. Keep direct writes
-- disabled; registration and key rotation are performed by authenticated API routes.
REVOKE INSERT, UPDATE, DELETE ON public.devices FROM anon, authenticated;
GRANT SELECT ON public.devices TO authenticated;

COMMIT;
