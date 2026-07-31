-- A merchant needs a shared secret to verify webhooks we send them, the mirror of the secret we
-- use to verify what acquirers send us.
--
-- Unlike an API key this cannot be stored as a hash: we have to reproduce the signature on every
-- delivery, so the plaintext has to be recoverable. In a real deployment this column belongs in a
-- secret manager or behind column encryption; storing it directly is a deliberate simplification
-- and the reason the value is only ever exposed through an admin-gated endpoint.
ALTER TABLE merchants ADD COLUMN webhook_secret VARCHAR(128);

-- Nullable rather than backfilled. A generated-in-SQL secret would not be cryptographically
-- random, and pretending otherwise is worse than having none: merchants onboarded before this
-- migration simply have no secret until one is issued for them.
COMMENT ON COLUMN merchants.webhook_secret IS
    'HMAC key for signing outbound webhooks. Null means this merchant cannot receive them yet.';
