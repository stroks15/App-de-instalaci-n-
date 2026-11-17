-- Defense in depth: session lifecycle and audit writes are server-owned.
DROP POLICY IF EXISTS "Users end own remote sessions" ON public.remote_sessions;
REVOKE UPDATE ON public.remote_sessions FROM authenticated;
DROP POLICY IF EXISTS "Users append own device audit logs" ON public.device_audit_logs;
REVOKE INSERT ON public.device_audit_logs FROM authenticated;
REVOKE UPDATE ON public.remote_sessions FROM authenticated;
GRANT SELECT, INSERT ON public.remote_sessions TO authenticated;
GRANT SELECT ON public.device_audit_logs TO authenticated;
