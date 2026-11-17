-- ============================================
-- 002: Tabla de usuarios + funciones de acceso permanente
-- IDEMPOTENTE: se puede re-ejecutar sin errores
-- ============================================
CREATE TABLE IF NOT EXISTS users (
  id UUID DEFAULT gen_random_uuid() PRIMARY KEY,
  email TEXT NOT NULL UNIQUE,
  name TEXT,
  avatar_url TEXT,
  is_permanent_paid BOOLEAN DEFAULT false,
  permanent_paid_at TIMESTAMPTZ,
  stripe_customer_id TEXT,
  subscription_status TEXT DEFAULT 'free',
  subscription_end TIMESTAMPTZ,
  created_at TIMESTAMPTZ DEFAULT NOW(),
  updated_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_users_email ON users(email);
CREATE INDEX IF NOT EXISTS idx_users_stripe_customer ON users(stripe_customer_id);
CREATE INDEX IF NOT EXISTS idx_users_permanent_paid ON users(is_permanent_paid);

ALTER TABLE users ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "Users can read own data" ON users;
CREATE POLICY "Users can read own data" ON users
  FOR SELECT USING (auth.uid() = id OR auth.email() = email);

DROP POLICY IF EXISTS "Users can update own data" ON users;
CREATE POLICY "Users can update own data" ON users
  FOR UPDATE USING (auth.uid() = id OR auth.email() = email);

DROP POLICY IF EXISTS "Service role full access users" ON users;
CREATE POLICY "Service role full access users" ON users
  FOR ALL USING (auth.role() = 'service_role');

-- Acceso permanente a la app (cupón DEXTERH4CK)
CREATE OR REPLACE FUNCTION has_permanent_access(user_email TEXT)
RETURNS BOOLEAN AS $$
DECLARE
  result BOOLEAN;
BEGIN
  SELECT is_permanent_paid INTO result FROM users WHERE email = user_email;
  RETURN COALESCE(result, false);
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

CREATE OR REPLACE FUNCTION grant_permanent_access(user_email TEXT)
RETURNS VOID AS $$
BEGIN
  UPDATE users
  SET is_permanent_paid = true, permanent_paid_at = NOW(), updated_at = NOW()
  WHERE email = user_email;
  IF NOT FOUND THEN
    INSERT INTO users (email, is_permanent_paid, permanent_paid_at)
    VALUES (user_email, true, NOW());
  END IF;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

SELECT 'users OK' AS resultado;