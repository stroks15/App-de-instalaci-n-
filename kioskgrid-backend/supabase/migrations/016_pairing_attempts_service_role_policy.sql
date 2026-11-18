-- Keep the private limiter table closed to clients while documenting backend-only access.
create policy service_role_manage_pairing_attempt_limits
on private.pairing_attempt_limits
for all
to service_role
using (true)
with check (true);
