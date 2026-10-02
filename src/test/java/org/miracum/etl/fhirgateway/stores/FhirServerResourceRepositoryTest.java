package org.miracum.etl.fhirgateway.stores;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.server.exceptions.UnclassifiedServerFailureException;
import io.micrometer.core.instrument.Metrics;
import org.hl7.fhir.r4.model.Bundle;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.springframework.retry.backoff.NoBackOffPolicy;
import org.springframework.retry.support.RetryTemplate;

class FhirServerResourceRepositoryTest {
  private static final FhirContext FHIR_CONTEXT = FhirContext.forR4();

  @Test
  void constructor_doesNotModifyTheSharedRetryTemplate() {
    var retryTemplate = mock(RetryTemplate.class);

    new FhirServerResourceRepository(FHIR_CONTEXT, mock(IGenericClient.class), retryTemplate);

    verifyNoInteractions(retryTemplate);
  }

  @Test
  void save_withFailedAttempt_countsTheError() {
    var client = mock(IGenericClient.class, Answers.RETURNS_DEEP_STUBS);
    when(client.transaction().withBundle(any(Bundle.class)).execute())
        .thenThrow(new UnclassifiedServerFailureException(503, "unavailable"))
        .thenReturn(new Bundle());
    var retryTemplate = new RetryTemplate();
    retryTemplate.setBackOffPolicy(new NoBackOffPolicy());
    var repository = new FhirServerResourceRepository(FHIR_CONTEXT, client, retryTemplate);
    var errorsBefore = countErrors();

    repository.save(new Bundle());

    assertThat(countErrors()).isEqualTo(errorsBefore + 1);
  }

  private static double countErrors() {
    return Metrics.globalRegistry.get("fhirgateway.fhirserver.transact.errors").counter().count();
  }
}
