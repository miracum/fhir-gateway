package org.miracum.etl.fhirgateway.processors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.List;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Resource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.kafka.support.KafkaNull;
import org.springframework.messaging.support.MessageBuilder;

@ExtendWith(MockitoExtension.class)
class BaseKafkaProcessorTest {

  @Mock private ResourcePipeline pipeline;

  private BaseKafkaProcessor createProcessor() {
    return new BaseKafkaProcessor(pipeline) {};
  }

  /**
   * Builds a 3-element batch payload with a tombstone in the middle, matching what
   * BatchMessagingMessageConverter#extractAndConvertValue actually produces for a null-value Kafka
   * record: a KafkaNull instance in the list, not a null element. The unchecked cast mirrors
   * reality - Spring doesn't enforce List&lt;Resource&gt; at runtime either.
   */
  @SuppressWarnings("unchecked")
  private static List<Resource> tombstoneBatch(Resource first, Resource last) {
    return (List<Resource>) (List<?>) Arrays.asList(first, KafkaNull.INSTANCE, last);
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

  @Test
  void processBatch_withTombstoneInBatch_isSkippedWithoutThrowing() {
    var processor = createProcessor();

    var patientA = new Patient();
    patientA.setId("Patient/a");
    var patientB = new Patient();
    patientB.setId("Patient/b");

    var bundleA = new Bundle();
    var bundleB = new Bundle();
    when(pipeline.processBatch(anyList())).thenReturn(List.of(bundleA, bundleB));

    var resources = tombstoneBatch(patientA, patientB);
    var message =
        MessageBuilder.withPayload(resources)
            .setHeader(KafkaHeaders.RECEIVED_TOPIC, List.of("fhir.all", "fhir.all", "fhir.all"))
            .setHeader(KafkaHeaders.RECEIVED_KEY, List.of("Patient/a", "tombstone", "Patient/b"))
            .build();

    var result = processor.processBatch(message);

    assertThat(result).containsExactly(bundleA, bundleB);

    var bundleCaptor = org.mockito.ArgumentCaptor.forClass(List.class);
    verify(pipeline).processBatch(bundleCaptor.capture());
    var forwarded = bundleCaptor.getValue();
    assertThat(forwarded).hasSize(2);
    assertThat(((Bundle) forwarded.get(0)).getEntryFirstRep().getResource()).isSameAs(patientA);
    assertThat(((Bundle) forwarded.get(1)).getEntryFirstRep().getResource()).isSameAs(patientB);
  }

  @Test
  void processBatchWithHeaders_withTombstoneInMiddle_keepsTopicAndKeyAlignedForSurvivors() {
    var processor = createProcessor();

    var patientA = new Patient();
    patientA.setId("Patient/a");
    var patientB = new Patient();
    patientB.setId("Patient/b");

    var bundleA = new Bundle();
    var bundleB = new Bundle();
    when(pipeline.processBatch(anyList())).thenReturn(List.of(bundleA, bundleB));

    var resources = tombstoneBatch(patientA, patientB);
    var message =
        MessageBuilder.withPayload(resources)
            .setHeader(KafkaHeaders.RECEIVED_TOPIC, List.of("fhir.all", "fhir.all", "fhir.all"))
            .setHeader(KafkaHeaders.RECEIVED_KEY, List.of("key-0", "key-1", "key-2"))
            .build();

    var result = processor.processBatchWithHeaders(message);

    assertThat(result).hasSize(2);
    assertThat(result.get(0).bundle()).isSameAs(bundleA);
    assertThat(result.get(0).key()).isEqualTo("key-0");
    assertThat(result.get(1).bundle()).isSameAs(bundleB);
    // without preserving the (bundle, topic, key) pairing across the filter, this would
    // incorrectly come back as "key-1" (the tombstone's key) instead of "key-2".
    assertThat(result.get(1).key()).isEqualTo("key-2");
  }
}
