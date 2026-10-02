package org.miracum.etl.fhirgateway.processors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.math.BigDecimal;
import java.net.URI;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Quantity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.miracum.etl.fhirgateway.FhirSystemsConfig;
import org.springframework.http.MediaType;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;

class LoincHarmonizerTest {
  private static final String LOINC_SYSTEM = "http://loinc.org";
  private static final String CONVERTER_URL = "http://loinc-converter/api/v1";

  private RestTemplate restTemplate;
  private MockRestServiceServer server;

  @BeforeEach
  void setUp() {
    restTemplate = new RestTemplate();
    server = MockRestServiceServer.bindTo(restTemplate).build();
  }

  @Test
  void process_withConvertibleQuantity_harmonizesValueUnitAndCode() {
    expectConversion("6.3", "{\"loinc\":\"2339-0\",\"unit\":\"mg/dL\",\"value\":\"113.526\"}");

    var harmonized = createHarmonizer(false).process(createGlucoseObservation());

    server.verify();
    assertThat(harmonized.getValueQuantity().getValue()).isEqualByComparingTo("113.526");
    assertThat(harmonized.getValueQuantity().getUnit()).isEqualTo("mg/dL");
    assertThat(harmonized.getValueQuantity().getCode()).isEqualTo("mg/dL");
    assertThat(harmonized.getCode().getCodingFirstRep().getCode()).isEqualTo("2339-0");
  }

  @Test
  void process_withReferenceRange_harmonizesItsBoundsToo() {
    expectConversion("6.3", "{\"loinc\":\"2339-0\",\"unit\":\"mg/dL\",\"value\":\"113.526\"}");
    expectConversion("3.9", "{\"loinc\":\"2339-0\",\"unit\":\"mg/dL\",\"value\":\"70.278\"}");
    expectConversion("5.5", "{\"loinc\":\"2339-0\",\"unit\":\"mg/dL\",\"value\":\"99.11\"}");
    var observation = createGlucoseObservation();
    observation
        .addReferenceRange()
        .setLow(createQuantity("3.9", "mmol/l"))
        .setHigh(createQuantity("5.5", "mmol/l"));

    var harmonized = createHarmonizer(false).process(observation);

    server.verify();
    var range = harmonized.getReferenceRangeFirstRep();
    assertThat(range.getLow().getValue()).isEqualByComparingTo("70.278");
    assertThat(range.getHigh().getValue()).isEqualByComparingTo("99.11");
    assertThat(range.getHigh().getUnit()).isEqualTo("mg/dL");
  }

  @Test
  void process_withoutLoincCoding_isReturnedUnchanged() {
    var observation = createGlucoseObservation();
    observation.getCode().getCodingFirstRep().setSystem("http://example.com/local-codes");

    var result = createHarmonizer(false).process(observation);

    server.verify();
    assertThat(result).isSameAs(observation);
  }

  @Test
  void process_withConversionFailure_returnsTheOriginalObservation() {
    server.expect(requestTo(startsWith(CONVERTER_URL))).andRespond(withServerError());
    var observation = createGlucoseObservation();

    var result = createHarmonizer(false).process(observation);

    assertThat(result).isSameAs(observation);
    assertThat(result.getValueQuantity().getValue()).isEqualByComparingTo("6.3");
  }

  @Test
  void process_withConversionFailureAndFailOnError_throws() {
    server.expect(requestTo(startsWith(CONVERTER_URL))).andRespond(withServerError());

    assertThatThrownBy(() -> createHarmonizer(true).process(createGlucoseObservation()))
        .isInstanceOf(HttpServerErrorException.class);
  }

  private void expectConversion(String value, String responseBody) {
    server
        .expect(requestTo(startsWith(CONVERTER_URL + "/conversions")))
        .andExpect(queryParam("loinc", "15074-8"))
        .andExpect(queryParam("unit", "mmol/l"))
        .andExpect(queryParam("value", value))
        .andRespond(withSuccess(responseBody, MediaType.APPLICATION_JSON));
  }

  private LoincHarmonizer createHarmonizer(boolean failOnError) {
    var retryTemplate = new RetryTemplate();
    retryTemplate.setRetryPolicy(new SimpleRetryPolicy(1));
    return new LoincHarmonizer(
        restTemplate,
        URI.create(CONVERTER_URL),
        new FhirSystemsConfig().setLoinc(LOINC_SYSTEM),
        failOnError,
        retryTemplate);
  }

  private static Observation createGlucoseObservation() {
    var observation = new Observation();
    observation.setId("Observation/glucose");
    observation.setCode(
        new CodeableConcept().addCoding(new Coding(LOINC_SYSTEM, "15074-8", "Glucose")));
    observation.setValue(createQuantity("6.3", "mmol/l"));
    return observation;
  }

  private static Quantity createQuantity(String value, String unit) {
    return new Quantity()
        .setValue(new BigDecimal(value))
        .setUnit(unit)
        .setCode(unit)
        .setSystem("http://unitsofmeasure.org");
  }
}
