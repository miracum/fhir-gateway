package org.miracum.etl.fhirgateway.processors;

import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Observation;
import org.miracum.etl.fhirgateway.stores.FhirResourceRepository;
import org.miracum.etl.fhirgateway.stores.FhirServerResourceRepository;
import org.miracum.etl.fhirgateway.stores.PostgresFhirResourceRepository;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ResourcePipeline {
  private static final Timer PIPELINE_DURATION_TIMER =
      Timer.builder("fhirgateway.pipeline.duration")
          .description("Total resource pipeline processing duration.")
          .minimumExpectedValue(Duration.ofMillis(50))
          .maximumExpectedValue(Duration.ofSeconds(5))
          .publishPercentileHistogram()
          .register(Metrics.globalRegistry);

  private final FhirResourceRepository psqlStore;
  private final FhirResourceRepository fhirStore;
  private final LoincHarmonizer loincHarmonizer;
  private final IPseudonymizer pseudonymizer;
  private final boolean isLoincHarmonizationEnabled;
  private final boolean isFhirServerEnabled;
  private final boolean isPsqlEnabled;

  public ResourcePipeline(
      PostgresFhirResourceRepository psqlStore,
      FhirServerResourceRepository fhirStore,
      LoincHarmonizer loincHarmonizer,
      IPseudonymizer pseudonymizer,
      @Value("${services.loinc.conversions.enabled}") boolean isLoincHarmonizationEnabled,
      @Value("${services.fhirServer.enabled}") boolean isFhirServerEnabled,
      @Value("${services.psql.enabled}") boolean isPsqlEnabled) {
    this.psqlStore = psqlStore;
    this.fhirStore = fhirStore;
    this.loincHarmonizer = loincHarmonizer;
    this.pseudonymizer = pseudonymizer;
    this.isLoincHarmonizationEnabled = isLoincHarmonizationEnabled;
    this.isFhirServerEnabled = isFhirServerEnabled;
    this.isPsqlEnabled = isPsqlEnabled;
  }

  private void saveToStores(Bundle bundle) {

    if (isFhirServerEnabled) {
      this.fhirStore.save(bundle);
    }

    if (isPsqlEnabled) {
      this.psqlStore.save(bundle);
    }
  }

  public Bundle process(Bundle bundle) {
    MDC.put("bundleId", bundle.getId());

    return PIPELINE_DURATION_TIMER.record(
        () -> {

          // pseudonymization should be the first task to ensure all other processors only
          // ever work with de-identified data.
          var pseudonymized = pseudonymizer.process(bundle);

          // this logic may be refactored and cleaned up by creating a genuine pipeline class with
          // optionally
          // added stages. A base for this would be an abstract ResourceProcessor
          if (this.isLoincHarmonizationEnabled) {
            for (var entry : pseudonymized.getEntry()) {
              var resource = entry.getResource();

              if (resource instanceof Observation observation) {
                try (var ignored = MDC.putCloseable("resourceId", resource.getId())) {
                  var obs = loincHarmonizer.process(observation);
                  entry.setResource(obs);
                }
              }
            }
          }

          saveToStores(pseudonymized);

          return pseudonymized;
        });
  }
}
