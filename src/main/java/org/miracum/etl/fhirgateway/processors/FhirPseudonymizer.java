package org.miracum.etl.fhirgateway.processors;

import static net.logstash.logback.argument.StructuredArguments.kv;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Parameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.retry.support.RetryTemplate;

public class FhirPseudonymizer implements IPseudonymizer {
  private static final Logger LOGGER = LoggerFactory.getLogger(FhirPseudonymizer.class);

  private static final Timer DE_IDENTIFICATION_DURATION_TIMER =
      Timer.builder("fhirgateway.deidentify.duration.seconds")
          .description("Time taken to de-identify the FHIR bundle")
          .minimumExpectedValue(Duration.ofMillis(1))
          .maximumExpectedValue(Duration.ofSeconds(10))
          .publishPercentileHistogram()
          .register(Metrics.globalRegistry);

  private final String pseudonymizerUrl;
  private final RetryTemplate retryTemplate;
  private final IGenericClient client;

  public FhirPseudonymizer(
      FhirContext fhirContext, String pseudonymizerUrl, RetryTemplate retryTemplate) {
    this.client = fhirContext.newRestfulGenericClient(pseudonymizerUrl);
    this.pseudonymizerUrl = pseudonymizerUrl;
    this.retryTemplate = retryTemplate;
  }

  @Override
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
}
