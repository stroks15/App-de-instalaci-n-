-- KioskGrid: pairing y propiedad de dispositivos.
-- Ejecutar después de 003_create_devices_table.sql.
ALTER TABLE public.devices
  ADD COLUMN IF NOT EXISTS hardware_id TEXT,
  ADD COLUMN IF NOT EXISTS pairing_code VARCHAR(5),
  ADD COLUMN IF NOT EXISTS user_id UUID REFERENCES auth.users(id) ON DELETE SET NULL,
  ADD COLUMN IF NOT EXISTS paired_at TIMESTAMPTZ;

CREATE UNIQUE INDEX IF NOT EXISTS devices_hardware_id_unique
  ON public.devices(hardware_id) WHERE hardware_id IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS devices_pairing_code_unique
  ON public.devices(pairing_code) WHERE pairing_code IS NOT NULL;
CREATE INDEX IF NOT EXISTS devices_user_id_idx ON public.devices(user_id);

ALTER TABLE public.devices
  DROP CONSTRAINT IF EXISTS devices_pairing_code_format;
ALTER TABLE public.devices
  ADD CONSTRAINT devices_pairing_code_format
  CHECK (pairing_code IS NULL OR pairing_code ~ '^[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{5}$');

-- El acceso público de lectura previo exponía identificadores y estado de todos los equipos.
DROP POLICY IF EXISTS "Allow public read devices" ON public.devices;
DROP POLICY IF EXISTS "Allow service role full access devices" ON public.devices;
CREATE POLICY "Users read own devices" ON public.devices
  FOR SELECT TO authenticated USING (user_id = auth.uid());
CREATE POLICY "Service role manages devices" ON public.devices
  FOR ALL TO service_role USING (true) WITH CHECK (true);

-- Los clientes anónimos no deben leer ni escribir dispositivos directamente.
