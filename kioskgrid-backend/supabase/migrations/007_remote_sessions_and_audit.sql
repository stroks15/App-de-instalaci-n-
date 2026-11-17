-- Remote management control plane. Video and input transport are intentionally not
-- implemented by this migration; sessions require explicit consent on the device.
CREATE TABLE IF NOT EXISTS public.remote_sessions (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  device_id uuid NOT NULL REFERENCES public.devices(id) ON DELETE CASCADE,
  owner_id uuid NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
  status text NOT NULL DEFAULT 'requested'
    CHECK (status IN ('requested', 'active', 'ended', 'expired', 'denied')),
  capabilities jsonb NOT NULL DEFAULT '{}'::jsonb,
  created_at timestamptz NOT NULL DEFAULT now(),
  started_at timestamptz,
  expires_at timestamptz NOT NULL DEFAULT (now() + interval '5 minutes'),
  ended_at timestamptz,
  CHECK (expires_at > created_at)
);

CREATE INDEX IF NOT EXISTS remote_sessions_owner_created_idx
  ON public.remote_sessions (owner_id, created_at DESC);
CREATE INDEX IF NOT EXISTS remote_sessions_device_status_idx
  ON public.remote_sessions (device_id, status, created_at DESC);

CREATE UNIQUE INDEX IF NOT EXISTS remote_sessions_one_open_per_device_idx
  ON public.remote_sessions (device_id)
  WHERE status IN ('requested', 'active');

ALTER TABLE public.remote_sessions ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "Users read own remote sessions" ON public.remote_sessions;
CREATE POLICY "Users read own remote sessions"
  ON public.remote_sessions FOR SELECT TO authenticated
  USING ((SELECT auth.uid()) = owner_id);

DROP POLICY IF EXISTS "Users create own remote sessions" ON public.remote_sessions;
CREATE POLICY "Users create own remote sessions"
  ON public.remote_sessions FOR INSERT TO authenticated
  WITH CHECK (
    (SELECT auth.uid()) = owner_id
    AND EXISTS (
      SELECT 1 FROM public.devices d
      WHERE d.id = device_id AND d.owner_id = (SELECT auth.uid())
    )
  );

DROP POLICY IF EXISTS "Users end own remote sessions" ON public.remote_sessions;
CREATE POLICY "Users end own remote sessions"
  ON public.remote_sessions FOR UPDATE TO authenticated
  USING ((SELECT auth.uid()) = owner_id)
  WITH CHECK ((SELECT auth.uid()) = owner_id);

CREATE TABLE IF NOT EXISTS public.device_audit_logs (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  device_id uuid NOT NULL REFERENCES public.devices(id) ON DELETE CASCADE,
  owner_id uuid NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
  actor_id uuid NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
  action text NOT NULL,
  metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
  created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS device_audit_logs_device_created_idx
  ON public.device_audit_logs (device_id, created_at DESC);
ALTER TABLE public.device_audit_logs ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "Users read own device audit logs" ON public.device_audit_logs;
CREATE POLICY "Users read own device audit logs"
  ON public.device_audit_logs FOR SELECT TO authenticated
  USING ((SELECT auth.uid()) = owner_id);

DROP POLICY IF EXISTS "Users append own device audit logs" ON public.device_audit_logs;
CREATE POLICY "Users append own device audit logs"
  ON public.device_audit_logs FOR INSERT TO authenticated
  WITH CHECK (
    (SELECT auth.uid()) = owner_id
    AND (SELECT auth.uid()) = actor_id
    AND EXISTS (
      SELECT 1 FROM public.devices d
      WHERE d.id = device_id AND d.owner_id = (SELECT auth.uid())
    )
  );

REVOKE ALL ON public.remote_sessions FROM anon;
REVOKE ALL ON public.device_audit_logs FROM anon;
GRANT SELECT, INSERT, UPDATE ON public.remote_sessions TO authenticated;
GRANT SELECT, INSERT ON public.device_audit_logs TO authenticated;
