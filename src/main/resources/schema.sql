CREATE TABLE IF NOT EXISTS resources
(
    id              serial,
    fhir_id         varchar(64) NOT NULL,
    type            varchar(64) NOT NULL,
    data            jsonb       NOT NULL,
    created_at      timestamp   NOT NULL DEFAULT NOW(),
    last_updated_at timestamp   NOT NULL DEFAULT NOW(),
    is_deleted      boolean     NOT NULL DEFAULT FALSE,
    CONSTRAINT fhir_id_unique UNIQUE (fhir_id, type)
);

CREATE UNIQUE INDEX CONCURRENTLY IF NOT EXISTS resource_id_idx ON resources (id);
CREATE INDEX IF NOT EXISTS resource_type_idx ON resources (type);
CREATE INDEX IF NOT EXISTS last_updated_at_idx ON resources (last_updated_at DESC);
