package org.miracum.etl.fhirgateway.processors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Resource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.support.MessageBuilder;

@ExtendWith(MockitoExtension.class)
class BaseKafkaProcessorTest {

  @Mock private ResourcePipeline pipeline;

  private BaseKafkaProcessor createProcessor() {
    return new BaseKafkaProcessor(pipeline) {};
  }

  @Test
  void processBatch_withResourcePayloads_isForwardedToPipeline() {
    var processor = createProcessor();

    var patient = new Patient();
    patient.setId("Patient/123");

    var expectedBundle = new Bundle();
    when(pipeline.processBatch(anyList())).thenReturn(List.of(expectedBundle));

    List<Resource> resources = List.of(patient);
    var message =
        MessageBuilder.withPayload(resources)
            .setHeader(KafkaHeaders.RECEIVED_TOPIC, List.of("fhir.all"))
            .setHeader(KafkaHeaders.RECEIVED_KEY, List.of("Patient/123"))
            .build();

    var result = processor.processBatch(message);

    assertThat(result).hasSize(1).containsExactly(expectedBundle);

    var bundleCaptor = org.mockito.ArgumentCaptor.forClass(List.class);
    verify(pipeline).processBatch(bundleCaptor.capture());

    var bundles = bundleCaptor.getValue();
    assertThat(bundles).hasSize(1);
    var bundle = (Bundle) bundles.get(0);
    assertThat(bundle.getEntry()).hasSize(1);
    assertThat(bundle.getEntryFirstRep().getResource()).isSameAs(patient);
  }
}
