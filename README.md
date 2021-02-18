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

| Variable                       | Description                                                                                                                                | Default                                   |
| ------------------------------ | ------------------------------------------------------------------------------------------------------------------------------------------ | ----------------------------------------- |
| SPRING_DATASOURCE_URL          | JDBC url of the Postgres DB to store the received FHIR resources, needs to be set to an empty variable if no PSQL db is to be connected to | jdbc:postgresql://fhir-db:5432/fhir       |
| SPRING_DATASOURCE_USERNAME     | Username of the Postgres DB                                                                                                                | postgres                                  |
| SPRING_DATASOURCE_PASSWORD     | Password for the Postgres DB                                                                                                               | postgres                                  |
| SERVICES_PSEUDONYMIZER_ENABLED | Whether pseudonymization should be enabled.                                                                                                | true                                     |
| GPAS_URL                       | URL of the gPAS service                                                                                                                    | <http://gpas:8080/gpas/gpasService>       |
| SERVICES_LOINC_CONVERSIONS_URL | URL of [the LOINC conversion service](https://gitlab.miracum.org/miracum/etl/loinc-conversion)                                             | <http://loinc-converter:8080/conversions> |
| SERVICES_FHIRSERVER_URL        | URL of the fhir server to send data to                                                                                                     | <http://fhir-server:8080/fhir>                   |
| SERVICES_FHIRSERVER_ENABLED    | enables or disables sending to fhir server                                                                                                 | false                                     |
| SERVICES_PSQL_ENABLED          | enables or disables sending to psql db                                                                                                     | true                                      |

For the Kafka configuration and other configuration options,
see [application.yml](src/main/resources/application.yml).

## Development

Start all fixtures to run the FHIR GW:

```shell
docker-compose -f deploy/docker-compose.dev.yml -f deploy/docker-compose.yml -f deploy/docker-compose.exposed.yml up
```

Note that this contains a few optional services: Kafka, a FHIR server, gPAS. You might simplify the
docker-compose.dev.yml and only include relevant components for development.
