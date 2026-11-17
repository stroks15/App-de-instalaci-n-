-- ============================================
-- 005: Notificaciones, modo kiosk, bloqueo de apps y uso
-- NUEVA MIGRACIÓN (funciones avanzadas)
-- IDEMPOTENTE: se puede re-ejecutar sin errores
-- ============================================

-- Columnas nuevas en devices (si ya existen, no hace nada)
ALTER TABLE devices ADD COLUMN IF NOT EXISTS fcm_token TEXT;
ALTER TABLE devices ADD COLUMN IF NOT EXISTS kiosk_enabled BOOLEAN DEFAULT false;
ALTER TABLE devices ADD COLUMN IF NOT EXISTS allowed_apps TEXT;
ALTER TABLE devices ADD COLUMN IF NOT EXISTS blocked_apps TEXT;

-- Tabla de notificaciones enviadas (historial)
CREATE TABLE IF NOT EXISTS notifications (
  id UUID DEFAULT gen_random_uuid() PRIMARY KEY,
  device_id TEXT,                    -- NULL = broadcast a todos
  title TEXT NOT NULL,
  body TEXT NOT NULL,
  status TEXT DEFAULT 'sent',
  created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_notifications_device ON notifications(device_id);
CREATE INDEX IF NOT EXISTS idx_notifications_created ON notifications(created_at);

ALTER TABLE notifications ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "notifications public read" ON notifications;
CREATE POLICY "notifications public read" ON notifications
  FOR SELECT USING (true);

DROP POLICY IF EXISTS "notifications service role" ON notifications;
CREATE POLICY "notifications service role" ON notifications
  FOR ALL USING (auth.role() = 'service_role');

-- Historial de uso de aplicaciones por equipo
CREATE TABLE IF NOT EXISTS app_usage (
  id UUID DEFAULT gen_random_uuid() PRIMARY KEY,
  device_id TEXT NOT NULL,
  package_name TEXT NOT NULL,
  app_name TEXT,
  usage_seconds INTEGER DEFAULT 0,
  reported_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_app_usage_device ON app_usage(device_id);
CREATE INDEX IF NOT EXISTS idx_app_usage_reported ON app_usage(reported_at);

ALTER TABLE app_usage ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "app_usage public read" ON app_usage;
CREATE POLICY "app_usage public read" ON app_usage
  FOR SELECT USING (true);

DROP POLICY IF EXISTS "app_usage service role" ON app_usage;
CREATE POLICY "app_usage service role" ON app_usage
  FOR ALL USING (auth.role() = 'service_role');

SELECT '005 avanzado OK' AS resultado;