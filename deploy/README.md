# Deployment using Compose

The [compose.yaml](compose.yaml) starts the FHIR Gateway, the LOINC Converter, the FHIR Pseudonymizer and the Gateway's PostgreSQL DB.

```sh
docker compose up
# or
nerdctl compose up
# or
podman-compose up
```

Simply POST any FHIR resource to <http://localhost:8080/fhir/>:

```sh
curl -d @../tests/e2e/data/bundle.json -H "Content-Type: application/fhir+json" -X POST http://localhost:8080/fhir
```
