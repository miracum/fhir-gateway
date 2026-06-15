package org.miracum.etl.fhirgateway.processors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Patient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.kafka.support.KafkaNull;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;

@ExtendWith(MockitoExtension.class)
class BaseKafkaProcessorTest {

  @Mock private ResourcePipeline pipeline;

  private BaseKafkaProcessor createProcessor() {
    return new BaseKafkaProcessor(pipeline) {};
  }

  @Test
  void process_withKafkaNullPayload_isIgnored() {
    var processor = createProcessor();

    Message<?> message =
        MessageBuilder.withPayload(KafkaNull.INSTANCE)
            .setHeader(KafkaHeaders.RECEIVED_TOPIC, "fhir.all")
            .setHeader(KafkaHeaders.RECEIVED_KEY, "some-key")
            .build();

    var result = processor.process(message);

    assertThat(result).isNull();
    verifyNoInteractions(pipeline);
  }

  @Test
  void process_withResourcePayload_isForwardedToPipeline() {
    var processor = createProcessor();

    var patient = new Patient();
    patient.setId("Patient/123");

    var expectedBundle = new Bundle();
    when(pipeline.process(any(Bundle.class))).thenReturn(expectedBundle);

    Message<?> message =
        MessageBuilder.withPayload(patient)
            .setHeader(KafkaHeaders.RECEIVED_TOPIC, "fhir.all")
            .setHeader(KafkaHeaders.RECEIVED_KEY, "Patient/123")
            .build();

    var result = processor.process(message);

    assertThat(result).isSameAs(expectedBundle);

    var bundleCaptor = org.mockito.ArgumentCaptor.forClass(Bundle.class);
    verify(pipeline).process(bundleCaptor.capture());

    var bundle = bundleCaptor.getValue();
    assertThat(bundle.getEntry()).hasSize(1);
    assertThat(bundle.getEntryFirstRep().getResource()).isSameAs(patient);
  }
}
