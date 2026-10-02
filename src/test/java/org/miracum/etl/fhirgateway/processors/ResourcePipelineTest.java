package org.miracum.etl.fhirgateway.processors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.hl7.fhir.r4.model.Bundle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.miracum.etl.fhirgateway.stores.PostgresFhirResourceRepository;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;

@ExtendWith(MockitoExtension.class)
class ResourcePipelineTest {

  @Mock private PostgresFhirResourceRepository psqlStore;

  @Test
  void process_setsTheBundleIdForLoggingOnlyWhileProcessing() {
    var bundleIdWhileSaving = new AtomicReference<String>();
    doAnswer(
            invocation -> {
              bundleIdWhileSaving.set(MDC.get("bundleId"));
              return null;
            })
        .when(psqlStore)
        .save(any(Bundle.class));
    var pipeline =
        new ResourcePipeline(
            Optional.empty(), Optional.of(psqlStore), Optional.empty(), Optional.empty());

    var bundle = new Bundle();
    bundle.setId("bundle-1");
    pipeline.process(bundle);

    assertThat(bundleIdWhileSaving).hasValue("bundle-1");
    assertThat(MDC.get("bundleId")).isNull();
  }
}
