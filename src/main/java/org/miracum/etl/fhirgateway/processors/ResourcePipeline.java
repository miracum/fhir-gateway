package org.miracum.etl.fhirgateway.processors;

import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.Optional;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Observation;
import org.miracum.etl.fhirgateway.stores.FhirServerResourceRepository;
import org.miracum.etl.fhirgateway.stores.PostgresFhirResourceRepository;
import org.slf4j.MDC;
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

  private final Optional<FhirServerResourceRepository> fhirStore;
  private final Optional<PostgresFhirResourceRepository> psqlStore;
  private final Optional<FhirPseudonymizer> pseudonymizer;
  private final Optional<LoincHarmonizer> loincHarmonizer;

  public ResourcePipeline(
      Optional<FhirServerResourceRepository> fhirStore,
      Optional<PostgresFhirResourceRepository> psqlStore,
      Optional<FhirPseudonymizer> pseudonymizer,
      Optional<LoincHarmonizer> loincHarmonizer) {
    this.fhirStore = fhirStore;
    this.psqlStore = psqlStore;
    this.pseudonymizer = pseudonymizer;
    this.loincHarmonizer = loincHarmonizer;
  }

  public Bundle process(Bundle bundle) {
    MDC.put("bundleId", bundle.getId());

    return PIPELINE_DURATION_TIMER.record(
        () -> {
          Bundle processing = bundle;
          // pseudonymization should be the first task to ensure all other processors only
          // ever work with de-identified data.
          if (pseudonymizer.isPresent()) {
            processing = pseudonymizer.get().process(processing);
          }

          // this logic may be refactored and cleaned up by creating a genuine pipeline class with
          // optionally added stages. A base for this would be an abstract ResourceProcessor
          if (loincHarmonizer.isPresent()) {
            for (var entry : processing.getEntry()) {
              var resource = entry.getResource();

              if (resource instanceof Observation observation) {
                try (var ignored = MDC.putCloseable("resourceId", resource.getId())) {
                  var obs = loincHarmonizer.get().process(observation);
                  entry.setResource(obs);
                }
              }
            }
          }

          saveToStores(processing);
          return processing;
        });
  }

  private void saveToStores(Bundle bundle) {

    if (fhirStore.isPresent()) {
      this.fhirStore.get().save(bundle);
    }

    if (psqlStore.isPresent()) {
      this.psqlStore.get().save(bundle);
    }
  }
}
