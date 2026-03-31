package org.miracum.etl.fhirgateway.processors;

import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
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

    try {
      return processAsync(bundle).get();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException("Pipeline processing interrupted", e);
    } catch (ExecutionException e) {
      throw new RuntimeException("Pipeline processing failed", e.getCause());
    }
  }

  public CompletableFuture<Bundle> processAsync(Bundle bundle) {
    MDC.put("bundleId", bundle.getId());

    var startTime = System.nanoTime();

    // Step 1: Pseudonymize (async)
    CompletableFuture<Bundle> pseudonymizationStep =
        pseudonymizer.isPresent()
            ? pseudonymizer.get().processAsync(bundle)
            : CompletableFuture.completedFuture(bundle);

    // Step 2: LOINC harmonization and storage (after pseudonymization)
    return pseudonymizationStep
        .thenApplyAsync(
            processing -> {
              // Apply LOINC harmonization
              if (loincHarmonizer.isPresent()) {
                for (var entry : processing.getEntry()) {
                  var resource = entry.getResource();

                  if (resource instanceof Observation observation) {
                    try (var _ = MDC.putCloseable("resourceId", resource.getId())) {
                      var obs = loincHarmonizer.get().process(observation);
                      entry.setResource(obs);
                    }
                  }
                }
              }
              return processing;
            })
        .thenApplyAsync(
            processing -> {
              saveToStores(processing);
              // Record the total pipeline duration
              PIPELINE_DURATION_TIMER.record(Duration.ofNanos(System.nanoTime() - startTime));
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
