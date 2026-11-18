-- Keep installation/update tasks available for up to 48 hours when local
-- Device Owner is unavailable and the TV user must explicitly accept.
ALTER TABLE public.tasks
  ADD COLUMN IF NOT EXISTS expires_at timestamptz;

UPDATE public.tasks
SET expires_at = COALESCE(expires_at, created_at + interval '48 hours')
WHERE expires_at IS NULL;

ALTER TABLE public.tasks
  ALTER COLUMN expires_at SET DEFAULT (now() + interval '48 hours');

CREATE INDEX IF NOT EXISTS tasks_expiry_status_idx
  ON public.tasks(status, expires_at);

COMMENT ON COLUMN public.tasks.expires_at IS
  'Task expiry; non-Device-Owner installation requests expire 48 hours after creation.';
