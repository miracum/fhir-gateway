package org.miracum.etl.fhirgateway.stores;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.CompletableFuture;
import org.apache.kafka.common.errors.RecordTooLargeException;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Patient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

@ExtendWith(MockitoExtension.class)
class KafkaFhirResourceRepositoryTest {

  @Mock private KafkaTemplate<String, Bundle> kafkaTemplate;

  @Test
  void save_withFailedSend_throws() {
    var failure = new RecordTooLargeException("message too large");
    when(kafkaTemplate.sendDefault(any(), any(Bundle.class)))
        .thenReturn(CompletableFuture.failedFuture(failure));
    var repository = new KafkaFhirResourceRepository("fhir.gateway.output", kafkaTemplate);

    assertThatThrownBy(() -> repository.save(createBundle()))
        .isInstanceOf(KafkaException.class)
        .hasCause(failure);
  }

  @Test
  void save_withSuccessfulSend_usesFirstFullUrlAsKey() {
    var bundle = createBundle();
    when(kafkaTemplate.sendDefault(any(), any(Bundle.class)))
        .thenReturn(CompletableFuture.completedFuture(new SendResult<>(null, null)));
    var repository = new KafkaFhirResourceRepository("fhir.gateway.output", kafkaTemplate);

    assertThatCode(() -> repository.save(bundle)).doesNotThrowAnyException();

    verify(kafkaTemplate).sendDefault(eq("Patient/123"), eq(bundle));
  }

  private static Bundle createBundle() {
    var patient = new Patient();
    patient.setId("Patient/123");

    var bundle = new Bundle();
    bundle.addEntry().setFullUrl("Patient/123").setResource(patient);
    return bundle;
  }
}
