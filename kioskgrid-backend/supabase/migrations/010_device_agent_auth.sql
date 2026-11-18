-- Align the deployed minimal device/task schema with the Android agent contract.
ALTER TABLE public.devices
  ALTER COLUMN owner_id DROP NOT NULL,
  ADD COLUMN IF NOT EXISTS hardware_id text,
  ADD COLUMN IF NOT EXISTS device_id text,
  ADD COLUMN IF NOT EXISTS pairing_code varchar(5),
  ADD COLUMN IF NOT EXISTS agent_key_hash text,
  ADD COLUMN IF NOT EXISTS paired_at timestamptz,
  ADD COLUMN IF NOT EXISTS updated_at timestamptz NOT NULL DEFAULT now(),
  ADD COLUMN IF NOT EXISTS type text NOT NULL DEFAULT 'android_tv',
  ADD COLUMN IF NOT EXISTS platform text NOT NULL DEFAULT 'android',
  ADD COLUMN IF NOT EXISTS app_version text NOT NULL DEFAULT '1.0.0',
  ADD COLUMN IF NOT EXISTS kiosk_enabled boolean NOT NULL DEFAULT false;

CREATE UNIQUE INDEX IF NOT EXISTS devices_hardware_id_unique
  ON public.devices(hardware_id) WHERE hardware_id IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS devices_device_id_unique
  ON public.devices(device_id) WHERE device_id IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS devices_pairing_code_unique
  ON public.devices(pairing_code) WHERE pairing_code IS NOT NULL;
CREATE INDEX IF NOT EXISTS devices_owner_id_idx ON public.devices(owner_id);

ALTER TABLE public.tasks
  ADD COLUMN IF NOT EXISTS result text,
  ADD COLUMN IF NOT EXISTS error text,
  ADD COLUMN IF NOT EXISTS updated_at timestamptz NOT NULL DEFAULT now();

CREATE INDEX IF NOT EXISTS tasks_device_status_created_idx
  ON public.tasks(device_id, status, created_at DESC);

-- No direct anonymous access to device/task records. Server routes enforce owner
-- or per-device bearer authentication before using the service-role client.
DROP POLICY IF EXISTS "Allow public read devices" ON public.devices;
DROP POLICY IF EXISTS "Users read own devices" ON public.devices;
CREATE POLICY "Users read own devices" ON public.devices
  FOR SELECT TO authenticated USING (owner_id = (SELECT auth.uid()));

DROP POLICY IF EXISTS "Allow public read tasks" ON public.tasks;
DROP POLICY IF EXISTS "Users read own tasks" ON public.tasks;
CREATE POLICY "Users read own tasks" ON public.tasks
  FOR SELECT TO authenticated USING (
    EXISTS (SELECT 1 FROM public.devices d
      WHERE d.id = tasks.device_id AND d.owner_id = (SELECT auth.uid()))
  );

REVOKE ALL ON public.devices FROM anon, authenticated;
GRANT SELECT ON public.devices TO authenticated;
REVOKE ALL ON public.tasks FROM anon, authenticated;
GRANT SELECT ON public.tasks TO authenticated;
