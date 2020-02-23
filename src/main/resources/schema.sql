CREATE TABLE IF NOT EXISTS resources
(
    id         SERIAL,
    fhir_id    varchar(64) NOT NULL,
    type       varchar(64) NOT NULL,
    data       jsonb       NOT NULL,
    created_at timestamp   NOT NULL DEFAULT NOW(),
    CONSTRAINT fhir_id_unique
        UNIQUE (fhir_id)
);
