-- ============================================
-- 004: Tabla de tareas (comandos remotos)
-- IDEMPOTENTE: se puede re-ejecutar sin errores
-- ============================================
CREATE TABLE IF NOT EXISTS tasks (
  id UUID DEFAULT gen_random_uuid() PRIMARY KEY,
  device_id TEXT NOT NULL,
  type TEXT NOT NULL,
  -- install | uninstall | launch | list_apps | device_info | reboot
  -- | pin_app | notify | kiosk_enable | kiosk_disable
  -- | block_app | unblock_app | get_usage
  payload TEXT,
  status TEXT DEFAULT 'queued',      -- queued | in_progress | completed | failed
  result TEXT,                       -- JSON con el resultado (apps, info, uso, etc.)
  error TEXT,
  created_at TIMESTAMPTZ DEFAULT NOW(),
  updated_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_tasks_device_id ON tasks(device_id);
CREATE INDEX IF NOT EXISTS idx_tasks_status ON tasks(status);
CREATE INDEX IF NOT EXISTS idx_tasks_type ON tasks(type);
CREATE INDEX IF NOT EXISTS idx_tasks_created_at ON tasks(created_at);

ALTER TABLE tasks ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "Allow public read tasks" ON tasks;
CREATE POLICY "Allow public read tasks" ON tasks
  FOR SELECT USING (true);

DROP POLICY IF EXISTS "Allow service role full access tasks" ON tasks;
CREATE POLICY "Allow service role full access tasks" ON tasks
  FOR ALL USING (auth.role() = 'service_role');

SELECT 'tasks OK' AS resultado;