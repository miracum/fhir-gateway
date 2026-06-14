package org.miracum.etl.fhirgateway.processors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.stream.IntStream;
import org.hl7.fhir.r4.model.Bundle;
import org.junit.jupiter.api.Test;

class ResourcePipelineTest {

  @Test
  void processBatch_returnsBundlesInInputOrder_evenIfPseudonymizationCompletesOutOfOrder() {
    var pseudonymizer = mock(FhirPseudonymizer.class);
    when(pseudonymizer.process(any(Bundle.class)))
        .thenAnswer(
            invocation -> {
              Bundle bundle = invocation.getArgument(0);
              // bundle "0" takes the longest, "9" the shortest, so completion order is the
              // reverse of input order.
              var index = Integer.parseInt(bundle.getId());
              Thread.sleep((10 - index) * 5L);
              return bundle;
            });

    var pipeline =
        new ResourcePipeline(
            Optional.empty(), Optional.empty(), Optional.of(pseudonymizer), Optional.empty());

    var bundles =
        IntStream.range(0, 10)
            .mapToObj(
                i -> {
                  var bundle = new Bundle();
                  bundle.setId(String.valueOf(i));
                  return bundle;
                })
            .toList();

    var result = pipeline.processBatch(bundles);

    var expectedIds = IntStream.range(0, 10).mapToObj(String::valueOf).toList();
    assertThat(result).extracting(Bundle::getId).containsExactlyElementsOf(expectedIds);
  }
}
