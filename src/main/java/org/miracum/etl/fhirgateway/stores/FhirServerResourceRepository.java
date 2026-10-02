package org.miracum.etl.fhirgateway.stores;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Metrics;
import org.hl7.fhir.r4.model.Bundle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnExpression("${services.fhirServer.enabled}")
public class FhirServerResourceRepository implements FhirResourceRepository {

  private static final Logger log = LoggerFactory.getLogger(FhirServerResourceRepository.class);

  private static final Counter SAVE_FAILED_COUNTER =
      Counter.builder("fhirgateway.fhirserver.transact.errors")
          .description("Number of failed attempts to send a FHIR bundle to the FHIR server")
          .register(Metrics.globalRegistry);

  private final FhirContext fhirContext;
  private final IGenericClient client;
  private final RetryTemplate retryTemplate;

  @Autowired
  public FhirServerResourceRepository(
      FhirContext fhirContext, IGenericClient client, RetryTemplate retryTemplate) {
    this.fhirContext = fhirContext;
    this.client = client;
    this.retryTemplate = retryTemplate;
  }

  @Override
  public void save(Bundle bundle) {
    if (log.isDebugEnabled()) {
      log.debug(
          "Sending bundle {} with contents {}",
          bundle,
          fhirContext.newJsonParser().encodeResourceToString(bundle));
    }

    var response =
        retryTemplate.execute(
            context -> {
              try {
                return client.transaction().withBundle(bundle).execute();
              } catch (RuntimeException exc) {
                SAVE_FAILED_COUNTER.increment();
                throw exc;
              }
            });

    if (log.isDebugEnabled()) {
      var parser = fhirContext.newJsonParser();
      log.debug(
          "Response for bundle {} with contents {}",
          parser.encodeResourceToString(bundle),
          parser.encodeResourceToString(response));
    }
  }
}
