CREATE TABLE IF NOT EXISTS flyway_phase_marker (
    service_name VARCHAR(100) PRIMARY KEY,
    initialized_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO flyway_phase_marker (service_name)
VALUES ('merchant-service')
ON CONFLICT (service_name) DO NOTHING;
