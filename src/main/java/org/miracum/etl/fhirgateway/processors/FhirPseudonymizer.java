package org.miracum.etl.fhirgateway.processors;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import java.util.HashMap;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Parameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.retry.backoff.FixedBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

public class FhirPseudonymizer implements IPseudonymizer {
  private static final Logger LOGGER = LoggerFactory.getLogger(FhirPseudonymizer.class);

  private final String pseudonymizerUrl;
  private final RetryTemplate retryTemplate;
  private final IGenericClient client;

  public FhirPseudonymizer(FhirContext fhirContext, String pseudonymizerUrl) {
    this.client = fhirContext.newRestfulGenericClient(pseudonymizerUrl);
    this.pseudonymizerUrl = pseudonymizerUrl;

    this.retryTemplate = new RetryTemplate();

    var fixedBackOffPolicy = new FixedBackOffPolicy();
    fixedBackOffPolicy.setBackOffPeriod(5_000);
    retryTemplate.setBackOffPolicy(fixedBackOffPolicy);

    var retryableExceptions = new HashMap<Class<? extends Throwable>, Boolean>();
    retryableExceptions.put(HttpClientErrorException.class, false);
    retryableExceptions.put(HttpServerErrorException.class, true);
    retryableExceptions.put(ResourceAccessException.class, true);

    retryTemplate.setRetryPolicy(new SimpleRetryPolicy(5, retryableExceptions));
  }

  @Override
  public Bundle process(Bundle bundle) {
    LOGGER.debug("Invoking pseudonymization service @ {}", pseudonymizerUrl);

    var param = new Parameters();
    param.addParameter().setName("resource").setResource(bundle);

    return retryTemplate.execute(
        ctx ->
            client
                .operation()
                .onServer()
                .named("de-identify")
                .withParameters(param)
                .returnResourceType(Bundle.class)
                .execute());
  }
}
