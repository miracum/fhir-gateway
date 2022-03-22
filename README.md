# FHIR Gateway

A thin layer between FHIR REST clients and resource processing pipelines.

## Overview

![Overview](docs/img/overview.png "Overview")

## Run it

See <https://github.com/num-codex/num-knoten> for an example deployment.

```sh
curl -d @tests/e2e/data/bundle.json -H "Content-Type: application/json" -X POST http://localhost:18080/fhir
```

## Configuration

To configure your deployment, you can change the following environment variables:

| Variable                                              | Description                                                                                                                                                                                                                                                                                                                          | Default                                   |
| ----------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | ----------------------------------------- |
| SPRING_DATASOURCE_URL                                 | JDBC URL of the Postgres DB to store the received FHIR resources, needs to be set to an empty variable if no PSQL db is to be connected to                                                                                                                                                                                           | jdbc:postgresql://fhir-db:5432/fhir       |
| SPRING_DATASOURCE_USERNAME                            | Username of the Postgres DB                                                                                                                                                                                                                                                                                                          | postgres                                  |
| SPRING_DATASOURCE_PASSWORD                            | Password for the Postgres DB                                                                                                                                                                                                                                                                                                         | postgres                                  |
| SERVICES_PSEUDONYMIZER_ENABLED                        | Whether pseudonymization should be enabled.                                                                                                                                                                                                                                                                                          | true                                      |
| SERVICES_PSEUDONYMIZER_URL                            | URL of the [FHIR Pseudonymizer service](https://github.com/miracum/fhir-pseudonymizer)                                                                                                                                                                                                                                               | <http://fhir-pseudonymizer:8080/fhir>     |
| SERVICES_LOINC_CONVERSIONS_URL                        | URL of the [LOINC conversion service](https://gitlab.miracum.org/miracum/etl/loinc-conversion)                                                                                                                                                                                                                                       | <http://loinc-converter:8080/conversions> |
| SERVICES_FHIRSERVER_ENABLED                           | Whether to send received resources to a downstream FHIR server                                                                                                                                                                                                                                                                       | false                                     |
| SERVICES_FHIRSERVER_URL                               | URL of the FHIR server to send data to                                                                                                                                                                                                                                                                                               | <http://fhir-server:8080/fhir>            |
| SERVICES_KAFKA_ENABLED                                | Enable reading FHIR resources from, and writing them back to a Kafka cluster                                                                                                                                                                                                                                                         | false                                     |
| SERVICES_KAFKA_GENERATE_OUTPUT_TOPIC_MATCH_EXPRESSION | Allows for dynamically generating the Kafka output topic's name based on the input topic. Used to set a regular expression which is applied to the input topic and the first match is replaced with the value of `SERVICES_KAFKA_GENERATE_OUTPUT_TOPIC_REPLACE_WITH`. You can set this to `"^"` to add a prefix to the output topic. | `""`                                      |

For the Kafka configuration and other configuration options,
see [application.yml](src/main/resources/application.yml).

## Supported Operations

The FHIR Gateway is not a fully-fledged FHIR server and only supports a subset of the RESTful server
interactions.

### POST/PUT

The Gateway only supports persisting resources that are HTTP POSTed as FHIR Bundles using
the [update-as-create](https://www.hl7.org/fhir/http.html#upsert) semantics.
See [bundle.json](tests/e2e/data/bundle.json) for an example.

### DELETE

FHIR Bundles containing `DELETE` requests are also handled and will result in deleting the resource
specified in the request URL. Note that the resources are marked as `is_deleted` in the Gateway's
PostgreSQL DB instead of being physically deleted.

Note that neither conditional creates nor deletes are supported. While this works:

```json
{
  "request": {
    "method": "DELETE",
    "url": "Patient/234"
  }
}
```

This does not:

```json
{
  "request": {
    "method": "DELETE",
    "url": "Patient?identifier=123456"
  }
}
```

## Development

Start all fixtures to run the FHIR GW:

```shell
docker-compose \
  -f deploy/docker-compose.dev.yml \
  -f deploy/docker-compose.gw-deps.yml \
  -f deploy/docker-compose.exposed.yml up
```

Note that this contains a few optional services: Kafka, a FHIR server, gPAS. You might simplify the
docker-compose.dev.yml and only include relevant components for development.

## Database Tuning

### Partitioning

If the size of the `resources` table is expected to grow significantly, you can leverage
partitioning to split the stored resources by type. Run the following **before** starting the
FHIR-Gateway to create the `resources` table with partitions for the most common resource types:

```postgresql
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
```

Be sure to set `SPRING_SQL_INIT_MODE=never` before starting the FHIR GW.

This isn't part of the default initialization schema, but may become the default as part of the next
major release.
