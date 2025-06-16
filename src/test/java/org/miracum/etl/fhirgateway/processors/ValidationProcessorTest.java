package org.miracum.etl.fhirgateway.processors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatRuntimeException;

import ca.uhn.fhir.context.FhirContext;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Bundle.BundleType;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Observation.ObservationStatus;
import org.hl7.fhir.r4.model.StringType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(classes = {ValidationProcessor.class, FhirContext.class})
@EnableConfigurationProperties(value = ValidationConfig.class)
class ValidationProcessorTest {

  @Autowired private ValidationProcessor processor;

  @Test
  void process_withValidResource_shouldSucceed() {
    var observation =
        new Observation()
            .setStatus(ObservationStatus.FINAL)
            .setCode(new CodeableConcept().setText("Test Observation"))
            .setValue(new StringType("Test Observation"));
    var bundle = new Bundle();
    bundle.setType(BundleType.TRANSACTION);
    bundle
        .addEntry()
        .setResource(observation)
        .getRequest()
        .setMethod(Bundle.HTTPVerb.PUT)
        .setUrl("Observation/test");
    processor.process(bundle);

    assertThat(bundle).isNotNull();
  }

  @Test
  void process_withInvalidResource_shouldThrowException() {
    var observation =
        new Observation()
            .setValue(new StringType("Test Observation missing required fields (status, code)"));
    var bundle = new Bundle();
    bundle.setType(BundleType.TRANSACTION);
    bundle
        .addEntry()
        .setResource(observation)
        .getRequest()
        .setMethod(Bundle.HTTPVerb.PUT)
        .setUrl("Observation/test");

    assertThatRuntimeException().isThrownBy(() -> processor.process(bundle));
  }
}
