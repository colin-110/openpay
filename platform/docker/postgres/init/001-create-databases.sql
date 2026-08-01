-- One database per service, because each service owns its schema.
--
-- Written to be re-runnable, which matters more than it looks. Postgres runs
-- /docker-entrypoint-initdb.d only when the data directory is empty, so on any volume that already
-- exists this file is never executed — and adding a service to it does nothing at all. The symptom
-- is the new service crash-looping on `database "openpay_x" does not exist` while every other
-- service is healthy, which points at the new service rather than at the volume.
--
-- The postgres-databases one-shot in docker-compose.yml runs this same file on every `up`, so a
-- stack that predates a new service picks it up. That only works if running it twice is harmless,
-- hence \gexec rather than a bare CREATE DATABASE.

SELECT 'CREATE DATABASE ' || quote_ident(name)
FROM (VALUES
    ('openpay_auth'),
    ('openpay_merchant'),
    ('openpay_payment'),
    ('openpay_router'),
    ('openpay_webhook'),
    ('openpay_ledger'),
    ('openpay_settlement'),
    ('openpay_notification'),
    ('openpay_fraud')
) AS wanted(name)
WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = wanted.name)
\gexec
