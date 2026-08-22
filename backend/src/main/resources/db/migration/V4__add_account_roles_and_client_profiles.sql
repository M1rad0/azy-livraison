ALTER TABLE app_account ADD COLUMN role VARCHAR(20);
ALTER TABLE app_account ADD COLUMN display_name VARCHAR(150);
ALTER TABLE app_account ADD COLUMN phone VARCHAR(50);

UPDATE app_account
SET role = 'ADMIN',
    display_name = username
WHERE role IS NULL;

ALTER TABLE app_account ALTER COLUMN role SET NOT NULL;
ALTER TABLE app_account ALTER COLUMN display_name SET NOT NULL;
ALTER TABLE app_account
    ADD CONSTRAINT app_account_role_check CHECK (role IN ('CLIENT', 'COURIER', 'ADMIN'));

CREATE TABLE client_profile (
    account_id UUID PRIMARY KEY REFERENCES app_account(id) ON DELETE CASCADE,
    default_pickup_label VARCHAR(200),
    default_pickup_query VARCHAR(500),
    default_pickup_latitude DOUBLE PRECISION,
    default_pickup_longitude DOUBLE PRECISION,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT client_profile_pickup_coordinates_pair_check CHECK (
        (default_pickup_latitude IS NULL AND default_pickup_longitude IS NULL)
        OR (default_pickup_latitude IS NOT NULL AND default_pickup_longitude IS NOT NULL)
    ),
    CONSTRAINT client_profile_pickup_latitude_check CHECK (
        default_pickup_latitude IS NULL
        OR default_pickup_latitude BETWEEN -90 AND 90
    ),
    CONSTRAINT client_profile_pickup_longitude_check CHECK (
        default_pickup_longitude IS NULL
        OR default_pickup_longitude BETWEEN -180 AND 180
    )
);
