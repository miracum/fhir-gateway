package org.miracum.etl.fhirgateway.processors;

import static net.logstash.logback.argument.StructuredArguments.kv;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.concurrent.Semaphore;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Parameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnExpression("${services.pseudonymizer.enabled}")
public class FhirPseudonymizer {
  private static final Logger LOGGER = LoggerFactory.getLogger(FhirPseudonymizer.class);

  private static final Timer DE_IDENTIFICATION_DURATION_TIMER =
      Timer.builder("fhirgateway.deidentify.duration")
          .description("Time taken to de-identify the FHIR bundle")
          .minimumExpectedValue(Duration.ofMillis(1))
          .maximumExpectedValue(Duration.ofSeconds(10))
          .publishPercentileHistogram()
          .register(Metrics.globalRegistry);

  private final String pseudonymizerUrl;
  private final RetryTemplate retryTemplate;
  private final IGenericClient client;

  // Caps how many de-identify calls are in flight at once
  private final Semaphore concurrencyLimiter;

  public FhirPseudonymizer(
      FhirContext fhirContext,
      @Value("${services.pseudonymizer.url}") String pseudonymizerUrl,
      @Value("${services.pseudonymizer.concurrency}") int concurrency,
      RetryTemplate retryTemplate) {
    this.client = fhirContext.newRestfulGenericClient(pseudonymizerUrl);
    this.pseudonymizerUrl = pseudonymizerUrl;
    this.retryTemplate = retryTemplate;
    this.concurrencyLimiter = new Semaphore(concurrency);
  }

  public Bundle process(Bundle bundle) {
    LOGGER.debug(
        "Invoking pseudonymization service @ {}", kv("pseudonymizerUrl", pseudonymizerUrl));

    var param = new Parameters();
    param.addParameter().setName("resource").setResource(bundle);

    try {
      concurrencyLimiter.acquire();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(
          "Interrupted while waiting for a pseudonymizer concurrency permit", e);
    }

    try {
      return DE_IDENTIFICATION_DURATION_TIMER.record(
          () ->
              retryTemplate.execute(
                  ctx ->
                      client
                          .operation()
                          .onServer()
                          .named("de-identify")
                          .withParameters(param)
                          .returnResourceType(Bundle.class)
                          .execute()));
    } finally {
      concurrencyLimiter.release();
    }
  }
}
