-- ============================================
-- 001: Tabla de cupones + cupón DEXTERH4CK
-- IDEMPOTENTE: se puede re-ejecutar sin errores
-- ============================================
CREATE TABLE IF NOT EXISTS coupons (
  id UUID DEFAULT gen_random_uuid() PRIMARY KEY,
  name TEXT NOT NULL UNIQUE,
  description TEXT,
  is_active BOOLEAN DEFAULT true,
  is_expired BOOLEAN DEFAULT false,
  discount DECIMAL(5,2) DEFAULT 0,
  type TEXT DEFAULT 'percentage',
  max_uses INTEGER,
  current_uses INTEGER DEFAULT 0,
  valid_from TIMESTAMPTZ DEFAULT NOW(),
  valid_until TIMESTAMPTZ,
  created_at TIMESTAMPTZ DEFAULT NOW(),
  updated_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_coupons_name ON coupons(name);
CREATE INDEX IF NOT EXISTS idx_coupons_is_active ON coupons(is_active);

ALTER TABLE coupons ENABLE ROW LEVEL SECURITY;

-- Re-crear políticas de forma segura
DROP POLICY IF EXISTS "Allow public read active coupons" ON coupons;
CREATE POLICY "Allow public read active coupons" ON coupons
  FOR SELECT USING (is_active = true AND is_expired = false);

DROP POLICY IF EXISTS "Allow authenticated read all coupons" ON coupons;
CREATE POLICY "Allow authenticated read all coupons" ON coupons
  FOR SELECT USING (auth.role() = 'authenticated');

DROP POLICY IF EXISTS "Allow service role full access coupons" ON coupons;
CREATE POLICY "Allow service role full access coupons" ON coupons
  FOR ALL USING (auth.role() = 'service_role');

-- Cupón DEXTERH4CK: 100% descuento, acceso permanente
INSERT INTO coupons (name, description, is_active, is_expired, discount, type, max_uses, current_uses)
VALUES (
  'DEXTERH4CK',
  'Permanent paid access unlock - Desbloquea la aplicación como pagada de forma permanente',
  true, false, 100.00, 'permanent', 1, 0
)
ON CONFLICT (name) DO UPDATE SET
  description = EXCLUDED.description,
  is_active = EXCLUDED.is_active,
  is_expired = EXCLUDED.is_expired,
  discount = EXCLUDED.discount,
  type = EXCLUDED.type;

SELECT 'cupones OK' AS resultado;