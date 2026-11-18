-- Remove broad default table grants; expose only the operations protected by RLS.
REVOKE ALL ON public.remote_sessions FROM PUBLIC, anon, authenticated;
REVOKE ALL ON public.device_audit_logs FROM PUBLIC, anon, authenticated;
GRANT SELECT, INSERT ON public.remote_sessions TO authenticated;
GRANT SELECT ON public.device_audit_logs TO authenticated;
