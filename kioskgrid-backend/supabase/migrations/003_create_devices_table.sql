-- ============================================
-- 003: Tabla de dispositivos (equipos)
-- IDEMPOTENTE: se puede re-ejecutar sin errores
-- ============================================
CREATE TABLE IF NOT EXISTS devices (
  id UUID DEFAULT gen_random_uuid() PRIMARY KEY,
  device_id TEXT UNIQUE NOT NULL,
  name TEXT NOT NULL,
  type TEXT DEFAULT 'android_tv',
  platform TEXT DEFAULT 'android',   -- android | fireos
  app_version TEXT DEFAULT '1.0.0',
  status TEXT DEFAULT 'offline',     -- online | offline | busy
  last_seen TIMESTAMPTZ DEFAULT NOW(),
  fcm_token TEXT,                    -- push (Google Android TV; Fire OS no tiene FCM)
  kiosk_enabled BOOLEAN DEFAULT false,
  allowed_apps TEXT,                 -- CSV de paquetes permitidos en modo kiosk
  blocked_apps TEXT,                 -- CSV de paquetes bloqueados
  created_at TIMESTAMPTZ DEFAULT NOW(),
  updated_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_devices_device_id ON devices(device_id);
CREATE INDEX IF NOT EXISTS idx_devices_status ON devices(status);
CREATE INDEX IF NOT EXISTS idx_devices_last_seen ON devices(last_seen);

ALTER TABLE devices ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "Allow public read devices" ON devices;
CREATE POLICY "Allow public read devices" ON devices
  FOR SELECT USING (true);

DROP POLICY IF EXISTS "Allow service role full access devices" ON devices;
CREATE POLICY "Allow service role full access devices" ON devices
  FOR ALL USING (auth.role() = 'service_role');

SELECT 'devices OK' AS resultado;
