CREATE TABLE geocoding_cache (
    normalized_query VARCHAR(300) NOT NULL,
    country_code CHAR(2) NOT NULL,
    provider VARCHAR(50) NOT NULL,
    response_json TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (normalized_query, country_code)
);

CREATE INDEX geocoding_cache_expires_at_idx ON geocoding_cache(expires_at);
