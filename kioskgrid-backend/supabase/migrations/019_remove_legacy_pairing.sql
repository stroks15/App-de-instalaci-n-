-- 017: remove the legacy manual pairing system after email-authenticated agent rollout.
BEGIN;

DROP FUNCTION IF EXISTS public.consume_pairing_attempt(text, integer, integer, integer);
DROP TABLE IF EXISTS private.pairing_attempt_limits;

DROP INDEX IF EXISTS public.devices_pairing_code_unique;
ALTER TABLE public.devices
  DROP CONSTRAINT IF EXISTS devices_pairing_code_format,
  DROP COLUMN IF EXISTS pairing_code,
  DROP COLUMN IF EXISTS pairing_expires_at,
  DROP COLUMN IF EXISTS paired_at;

COMMIT;
