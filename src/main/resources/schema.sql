CREATE TABLE IF NOT EXISTS resources
(
    id              bigserial PRIMARY KEY,
    patient_id      varchar(64) NOT NULL,
    encounter_id    varchar(64) NOT NULL,
    fhir_id         varchar(64) NOT NULL,
    type            varchar(64) NOT NULL,
    data            jsonb       NOT NULL,
    created_at      timestamp   NOT NULL DEFAULT NOW(),
    last_updated_at timestamp   NOT NULL DEFAULT NOW(),
    is_deleted      boolean     NOT NULL DEFAULT FALSE,
    CONSTRAINT fhir_id_unique UNIQUE (fhir_id, type)
) PARTITION BY LIST (type);

CREATE TABLE resources_patient PARTITION OF resources FOR VALUES IN ('Patient');
CREATE TABLE resources_encounter PARTITION OF resources FOR VALUES IN ('Encounter');
CREATE TABLE resources_condition PARTITION OF resources FOR VALUES IN ('Condition');
CREATE TABLE resources_observation PARTITION OF resources FOR VALUES IN ('Observation');
CREATE TABLE resources_medication PARTITION OF resources FOR VALUES IN ('Medication');
CREATE TABLE resources_medication_statement PARTITION OF resources FOR VALUES IN ('MedicationStatement');
CREATE TABLE resources_medication_administration PARTITION OF resources FOR VALUES IN ('MedicationAdministration');
CREATE TABLE resources_procedure PARTITION OF resources FOR VALUES IN ('Procedure');
CREATE TABLE resources_others PARTITION OF resources DEFAULT;

CREATE UNIQUE INDEX CONCURRENTLY IF NOT EXISTS resource_id_idx ON resources (id);
CREATE INDEX IF NOT EXISTS resource_type_idx ON resources (type);
CREATE INDEX IF NOT EXISTS last_updated_at_idx ON resources (last_updated_at DESC);

CREATE INDEX IF NOT EXISTS resource_pat_id_idx ON resources (patient_id);
CREATE INDEX IF NOT EXISTS resource_enc_id_idx ON resources (encounter_id);
