CREATE TABLE app_health_probe (
    id SMALLINT PRIMARY KEY,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT app_health_probe_single_row CHECK (id = 1)
);

INSERT INTO app_health_probe (id) VALUES (1);

