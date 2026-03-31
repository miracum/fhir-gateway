package org.miracum.etl.fhirgateway.processors;

import static net.logstash.logback.argument.StructuredArguments.kv;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Parameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
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
  private final Executor asyncExecutor;

  public FhirPseudonymizer(
      FhirContext fhirContext,
      @Value("${services.pseudonymizer.url}") String pseudonymizerUrl,
      RetryTemplate retryTemplate,
      @Qualifier("pseudonymizerExecutor") Executor asyncExecutor) {
    this.client = fhirContext.newRestfulGenericClient(pseudonymizerUrl);
    this.pseudonymizerUrl = pseudonymizerUrl;
    this.retryTemplate = retryTemplate;
    this.asyncExecutor = asyncExecutor;
  }

  public Bundle process(Bundle bundle) {
    LOGGER.debug(
        "Invoking pseudonymization service @ {}", kv("pseudonymizerUrl", pseudonymizerUrl));

    var param = new Parameters();
    param.addParameter().setName("resource").setResource(bundle);

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
  }

  public CompletableFuture<Bundle> processAsync(Bundle bundle) {
    LOGGER.debug(
        "Invoking pseudonymization service (async) @ {}", kv("pseudonymizerUrl", pseudonymizerUrl));

    return CompletableFuture.supplyAsync(
        () -> {
          var param = new Parameters();
          param.addParameter().setName("resource").setResource(bundle);

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
        },
        asyncExecutor);
  }
}
