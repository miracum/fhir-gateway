package org.miracum.etl.fhirgateway.processors;

import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
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
          .maximumExpectedValue(Duration.ofSeconds(10))
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
    return processBatch(List.of(bundle)).get(0);
  }

  public List<Bundle> processBatch(List<Bundle> bundles) {
    if (bundles.isEmpty()) {
      return List.of();
    }

    return PIPELINE_DURATION_TIMER.record(
        () -> pseudonymizeConcurrently(bundles).stream().map(this::runRemainingStages).toList());
  }

  private Bundle pseudonymizeSingle(Bundle bundle) {
    if (pseudonymizer.isEmpty()) {
      return bundle;
    }

    return pseudonymizer.get().process(bundle);
  }

  private List<Bundle> pseudonymizeConcurrently(List<Bundle> bundles) {
    if (pseudonymizer.isEmpty() || bundles.size() == 1) {
      return bundles.stream().map(this::pseudonymizeSingle).toList();
    }

    var tasks = bundles.stream().<Callable<Bundle>>map(b -> () -> pseudonymizeSingle(b)).toList();

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // invokeAll returns futures in the same order as `tasks`, regardless of completion
      // order, so `result` stays aligned with the input `bundles` list.
      var futures = executor.invokeAll(tasks);
      var result = new ArrayList<Bundle>(futures.size());
      for (var future : futures) {
        result.add(future.get());
      }
      return result;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while pseudonymizing bundle batch", e);
    } catch (ExecutionException e) {
      throw new IllegalStateException("Failed to pseudonymize bundle", e.getCause());
    }
  }

  private Bundle runRemainingStages(Bundle bundle) {
    MDC.put("bundleId", bundle.getId());

    if (loincHarmonizer.isPresent()) {
      for (var entry : bundle.getEntry()) {
        var resource = entry.getResource();

        if (resource instanceof Observation observation) {
          try (var _ = MDC.putCloseable("resourceId", resource.getId())) {
            var obs = loincHarmonizer.get().process(observation);
            entry.setResource(obs);
          }
        }
      }
    }

    saveToStores(bundle);
    return bundle;
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
