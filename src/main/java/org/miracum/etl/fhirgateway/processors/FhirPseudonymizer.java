package org.miracum.etl.fhirgateway.processors;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Parameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.retry.support.RetryTemplate;

public class FhirPseudonymizer implements IPseudonymizer {
  private static final Logger LOGGER = LoggerFactory.getLogger(FhirPseudonymizer.class);

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
    LOGGER.debug("Invoking pseudonymization service @ requestUrl={}", pseudonymizerUrl);

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
