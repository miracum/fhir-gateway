package org.miracum.etl.fhirgateway.stores;

import static net.logstash.logback.argument.StructuredArguments.kv;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.IParser;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Bundle.BundleEntryComponent;
import org.hl7.fhir.r4.model.Bundle.HTTPVerb;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;

@Component
public class PostgresFhirResourceRepository implements FhirResourceRepository {
  private static final Timer INSERT_DURATION_TIMER =
      Timer.builder("fhirgateway.postgres.operation.duration")
          .description("Time taken to store all resources from a FHIR bundle in the database")
          .minimumExpectedValue(Duration.ofMillis(10))
          .maximumExpectedValue(Duration.ofSeconds(5))
          .publishPercentileHistogram()
          .tags("operation", "insert")
          .register(Metrics.globalRegistry);
  private static final Timer DELETE_DURATION_TIMER =
      Timer.builder("fhirgateway.postgres.operation.duration")
          .description("Time taken to delete all resources from a FHIR bundle from the database")
          .minimumExpectedValue(Duration.ofMillis(10))
          .maximumExpectedValue(Duration.ofSeconds(5))
          .publishPercentileHistogram()
          .tags("operation", "delete")
          .register(Metrics.globalRegistry);

  private static final Logger log = LoggerFactory.getLogger(PostgresFhirResourceRepository.class);

  private static final AtomicInteger batchUpdateFailed =
      Metrics.globalRegistry.gauge(
          "fhirgateway.postgres.batchupdate.errors.total", new AtomicInteger(0));

  private final IParser fhirParser;
  private final JdbcTemplate dataSinkTemplate;
  private final RetryTemplate retryTemplate;

  @Autowired
  public PostgresFhirResourceRepository(
      FhirContext fhirContext,
      JdbcTemplate dataSinkTemplate,
      @Qualifier("databaseRetryTemplate") RetryTemplate retryTemplate) {
    this.fhirParser = fhirContext.newJsonParser();
    this.dataSinkTemplate = dataSinkTemplate;
    this.retryTemplate = retryTemplate;
  }

  @Override
  public void save(Bundle bundle) {
    var insertedCount = insertResources(bundle);
    var deletedCount = deleteResources(bundle);

    log.debug(
        "processed bundle {}, {}",
        kv("insertedCount", insertedCount),
        kv("deletedCount", deletedCount));
  }

  private int insertResources(Bundle bundle) {
    var insertValues =
        bundle.getEntry().stream()
            // all but delete operations should result in persisting the included resource
            // ignoring HTTP patch for now.
            .filter(e -> e.getRequest().getMethod() != HTTPVerb.DELETE)
            .map(BundleEntryComponent::getResource)
            .sorted(Comparator.comparing(r -> r.getIdElement().getIdPart()))
            .map(
                resource ->
                    new Object[] {
                      // TODO add encounter id / patient id / encounter_start date here
                      // this would depend also on the resources, e.g.
                      // type = "Patient": Encounter-ID: NULL, Patient-ID: Patient.id
                      // type = "Encounter": Encounter-ID: Encounter.id, Patient.id:
                      // REPLACE(jsonb_path_query(Encounter.DATA, '$.subject') ->> 'reference',
                      // 'Patient/', '')
                      // type = "Condition": Encounter-ID: REPLACE(Condition.DATA -> 'encounter' ->>
                      // 'reference', 'Encounter/', '')
                      // type = "Procedure": Encounter-ID: REPLACE(Procedure.DATA -> 'encounter' ->>
                      // 'reference', 'Encounter/', '')
                      // type = "Observation": Encounter-ID: REPLACE(Observation.DATA -> 'encounter'
                      // ->> 'reference', 'Encounter/', '')
                      resource.getIdElement().getIdPart(),
                      resource.fhirType(),
                      fhirParser.encodeResourceToString(resource)
                    })
            .collect(Collectors.toCollection(ArrayList::new));

    if (!insertValues.isEmpty()) {
      INSERT_DURATION_TIMER.record(
          () ->
              retryTemplate.execute(
                  (context) ->
                      dataSinkTemplate.batchUpdate(
                          "INSERT INTO resources (fhir_id, type, data)"
                              + "VALUES (?, ?, ?::json)"
                              + "ON CONFLICT (fhir_id, type)"
                              + "DO UPDATE set data = EXCLUDED.data, last_updated_at = NOW(), is_deleted = false",
                          insertValues)));
    }

    return insertValues.size();
  }

  private int deleteResources(Bundle bundle) {
    var deleteValues =
        bundle.getEntry().stream()
            .map(BundleEntryComponent::getRequest)
            .filter(request -> request.getMethod() == HTTPVerb.DELETE)
            .map(request -> (Object[]) request.getUrl().split("/"))
            .collect(Collectors.toCollection(ArrayList::new));

    if (!deleteValues.isEmpty()) {
      DELETE_DURATION_TIMER.record(
          () ->
              retryTemplate.execute(
                  (context) ->
                      dataSinkTemplate.batchUpdate(
                          "UPDATE resources "
                              + "SET last_updated_at = NOW(), is_deleted = true "
                              + "WHERE type = ? AND fhir_id = ?",
                          deleteValues)));
    }

    return deleteValues.size();
  }
}
