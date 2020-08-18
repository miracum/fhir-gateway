package org.miracum.etl.fhirgateway.processors;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Metrics;
import java.net.URI;
import java.util.HashMap;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Quantity;
import org.miracum.etl.fhirgateway.FhirSystemsConfig;
import org.miracum.etl.fhirgateway.models.loinc.LoincConversion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.util.Pair;
import org.springframework.retry.backoff.FixedBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

@Service
public class LoincHarmonizer {
  private static final Logger log = LoggerFactory.getLogger(LoincHarmonizer.class);

  private static final String CONVERSION_ERROR_METRIC_NAME =
      "fhirgateway.loinc.conversion.errors.total";
  private static final HashMap<String, Counter> metricsLookup = new HashMap<>();

  private final RestTemplate restTemplate;
  private final URI loincConverterBaseUri;
  private final FhirSystemsConfig fhirSystems;
  private final RetryTemplate retryTemplate;
  private final boolean failOnError;

  public LoincHarmonizer(
      RestTemplate restTemplate,
      @Value("${services.loinc.conversions.url}") URI loincConverterUri,
      FhirSystemsConfig fhirSystems,
      @Value("${services.loinc.conversions.failOnError}") boolean failOnError) {
    this.restTemplate = restTemplate;
    this.loincConverterBaseUri = loincConverterUri;
    this.fhirSystems = fhirSystems;
    this.failOnError = failOnError;
    this.retryTemplate = new RetryTemplate();

    var fixedBackOffPolicy = new FixedBackOffPolicy();
    fixedBackOffPolicy.setBackOffPeriod(5_000);
    retryTemplate.setBackOffPolicy(fixedBackOffPolicy);

    var retryableExceptions = new HashMap<Class<? extends Throwable>, Boolean>();
    retryableExceptions.put(HttpClientErrorException.class, false);
    retryableExceptions.put(HttpServerErrorException.class, true);

    retryTemplate.setRetryPolicy(new SimpleRetryPolicy(5, retryableExceptions));
  }

  public Observation process(final Observation originalObservation) {
    var loincCode =
        originalObservation.getCode().getCoding().stream()
            .filter(obs -> obs.getSystem().equals(fhirSystems.getLoinc()))
            .findFirst();

    var harmonized = originalObservation.copy();

    // only process observation resources with a set quantity and code
    if (loincCode.isEmpty()
        || !originalObservation.hasValueQuantity()
        || !originalObservation.getValueQuantity().hasUnit()) {
      return harmonized;
    }

    try {
      var originalCode = loincCode.get().getCode();
      // harmonize the observation's main code/value
      var result = getHarmonizedQuantity(originalObservation.getValueQuantity(), originalCode);

      if (result != null) {
        harmonized.setValue(result.getFirst());
        harmonized.getCode().getCodingFirstRep().setCode(result.getSecond());

        // if the LOINC code has changed, the display is most likely incorrect, just drop it
        if (!originalCode.equals(result.getSecond())) {
          harmonized.getCode().getCodingFirstRep().setDisplay(null);
        }
      }

      if (!originalObservation.hasReferenceRange()) {
        return harmonized;
      }

      // harmonize the reference range
      for (var rangeComponent : harmonized.getReferenceRange()) {
        if (rangeComponent.hasLow()) {
          var rangeLow = rangeComponent.getLow();

          result = getHarmonizedQuantity(rangeLow, originalCode);

          if (result != null) {
            rangeComponent.setLow(result.getFirst());
          }
        }

        if (rangeComponent.hasHigh()) {
          var rangeHigh = rangeComponent.getHigh();

          result = getHarmonizedQuantity(rangeHigh, originalCode);

          if (result != null) {
            rangeComponent.setHigh(result.getFirst());
          }
        }
      }
    } catch (Exception exc) {
      log.debug(
          "LOINC harmonization failure for observation id={} (loinc={}; unit={}).",
          originalObservation.getId(),
          loincCode.orElse(null).getCode(),
          originalObservation.getValueQuantity().getUnit(),
          exc);

      var unit = originalObservation.getValueQuantity().getUnit();
      metricsLookup.putIfAbsent(
          unit, Metrics.globalRegistry.counter(CONVERSION_ERROR_METRIC_NAME, "unit", unit));
      metricsLookup.get(unit).increment();

      if (this.failOnError) {
        throw exc;
      }
      return originalObservation;
    }

    return harmonized;
  }

  private Pair<Quantity, String> getHarmonizedQuantity(Quantity input, String loincCode) {
    var requestUrl =
        UriComponentsBuilder.fromUri(loincConverterBaseUri)
            .path("/conversions")
            .queryParam("loinc", loincCode)
            .queryParam("unit", input.getUnit())
            .queryParam("value", input.getValue())
            .build()
            .encode()
            .toUri();

    log.debug("Invoking harmonization service @ {}", requestUrl);

    var response =
        retryTemplate.execute(ctx -> restTemplate.getForObject(requestUrl, LoincConversion.class));

    if (response == null) {
      throw new RuntimeException("LOINC conversion service returned empty result.");
    }

    if (response.getValue() != null && response.getUnit() != null && response.getLoinc() != null) {
      var quantity = new Quantity();
      quantity.setValue(response.getValue());
      quantity.setUnit(response.getUnit());
      quantity.setCode(response.getUnit());
      quantity.setSystem(input.getSystem());
      return Pair.of(quantity, response.getLoinc());
    }

    return null;
  }
}
