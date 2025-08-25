package org.miracum.etl.fhirgateway.stores;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.IParser;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import io.micrometer.core.instrument.Metrics;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import org.hl7.fhir.r4.model.Bundle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.retry.RetryCallback;
import org.springframework.retry.RetryContext;
import org.springframework.retry.RetryListener;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnExpression("${services.fhirServer.enabled}")
public class FhirServerResourceRepository implements FhirResourceRepository {

  private static final Logger log = LoggerFactory.getLogger(FhirServerResourceRepository.class);

  private static final AtomicInteger saveFailedCounter =
      Metrics.globalRegistry.gauge(
          "fhirgateway.fhirserver.transact.errors.total", new AtomicInteger(0));

  private final IParser fhirParser;
  private final IGenericClient client;
  private final RetryTemplate retryTemplate;

  @Autowired
  public FhirServerResourceRepository(
      FhirContext fhirContext, IGenericClient client, RetryTemplate retryTemplate) {

    this.fhirParser = fhirContext.newJsonParser();
    this.client = client;
    this.retryTemplate = retryTemplate;
    this.retryTemplate.registerListener(
        new RetryListener() {
          @Override
          public <T, E extends Throwable> void onError(
              RetryContext context, RetryCallback<T, E> callback, Throwable throwable) {
            log.warn(
                "Trying to sent resource to FHIR server caused error. {} attempt.",
                context.getRetryCount(),
                throwable);
            Objects.requireNonNull(saveFailedCounter).incrementAndGet();
          }
        });
  }

  @Override
  public void save(Bundle bundle) {
    log.debug(
        "Sending bundle {} with contents {}", bundle, fhirParser.encodeResourceToString(bundle));

    var response =
        retryTemplate.execute(context -> client.transaction().withBundle(bundle).execute());

    log.debug(
        "Response for bundle {} with contents {}",
        fhirParser.encodeResourceToString(bundle),
        fhirParser.encodeResourceToString(response));
  }
}
