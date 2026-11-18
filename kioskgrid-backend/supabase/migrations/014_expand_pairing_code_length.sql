-- Expand pairing-code storage for higher-entropy eight-character codes.
-- Existing five-character codes remain valid during the transition.

alter table public.devices
  alter column pairing_code type varchar(8);
