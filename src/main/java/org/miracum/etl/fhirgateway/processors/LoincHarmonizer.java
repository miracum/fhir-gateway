package org.miracum.etl.fhirgateway.processors;

import static net.logstash.logback.argument.StructuredArguments.kv;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;
import java.net.URI;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Quantity;
import org.miracum.etl.fhirgateway.FhirSystemsConfig;
import org.miracum.etl.fhirgateway.models.loinc.LoincConversion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.util.Pair;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriTemplate;

@Service
public class LoincHarmonizer {
  private static final Logger log = LoggerFactory.getLogger(LoincHarmonizer.class);
  private static final Timer CONVERSION_DURATION_TIMER =
      Timer.builder("fhirgateway.loinc.conversion.duration")
          .description("Time taken to harmonize a single Observation resource")
          .minimumExpectedValue(Duration.ofMillis(5))
          .maximumExpectedValue(Duration.ofSeconds(5))
          .publishPercentileHistogram()
          .register(Metrics.globalRegistry);
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
      @Value("${services.loinc.conversions.failOnError}") boolean failOnError,
      RetryTemplate retryTemplate) {
    this.restTemplate = restTemplate;
    this.loincConverterBaseUri = loincConverterUri;
    this.fhirSystems = fhirSystems;
    this.failOnError = failOnError;
    this.retryTemplate = retryTemplate;
  }

  public Observation process(final Observation originalObservation) {
    return CONVERSION_DURATION_TIMER.record(() -> harmonizeObservation(originalObservation));
  }

  private Observation harmonizeObservation(final Observation originalObservation) {
    var loincCode =
        originalObservation.getCode().getCoding().stream()
            .filter(obs -> obs.getSystem().equals(fhirSystems.getLoinc()))
            .findFirst();

    var harmonized = originalObservation.copy();

    // only process observation resources with a set quantity and code
    if (loincCode.isEmpty()
        || !originalObservation.hasValueQuantity()
        || !originalObservation.getValueQuantity().hasCode()) {
      return originalObservation;
    }

    try {
      var originalCode = loincCode.get().getCode();
      // harmonize the observation's main code/value
      var result = getHarmonizedQuantity(originalObservation.getValueQuantity(), originalCode);

      if (result != null) {
        harmonized.setValue(result.getFirst());
        Pair<Quantity, LoincConversion> finalResult = result;
        harmonized.getCode().getCoding().stream()
            .filter(obs -> obs.getSystem().equals(fhirSystems.getLoinc()))
            .findFirst()
            .ifPresent(
                loinc -> {
                  loinc.setCode(finalResult.getSecond().getLoinc());
                  loinc.setDisplay(finalResult.getSecond().getDisplay());
                });
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
          "LOINC harmonization failure {}; {}; {}",
          kv("loinc", loincCode.orElse(new Coding()).getCode()),
          kv("unit", originalObservation.getValueQuantity().getUnit()),
          kv("code", originalObservation.getValueQuantity().getCode()),
          exc);

      var unitcode = originalObservation.getValueQuantity().getCode();
      metricsLookup.putIfAbsent(
          unitcode,
          Metrics.globalRegistry.counter(CONVERSION_ERROR_METRIC_NAME, "unitcode", unitcode));
      metricsLookup.get(unitcode).increment();

      if (this.failOnError) {
        throw exc;
      }

      return originalObservation;
    }

    return harmonized;
  }

  private Pair<Quantity, LoincConversion> getHarmonizedQuantity(Quantity input, String loincCode) {

    var requestUrl =
        UriComponentsBuilder.fromUri(loincConverterBaseUri)
            .path("/conversions")
            .queryParam("loinc", "{loinc}")
            .queryParam("unit", "{unit}")
            .queryParam("value", "{value}")
            .build()
            .toUriString();

    var response =
        retryTemplate.execute(
            ctx -> {
              var templateVars =
                  Map.of("loinc", loincCode, "unit", input.getCode(), "value", input.getValue());
              log.debug(
                  "Invoking LOINC harmonization service @ {}",
                  kv("requestUrl", new UriTemplate(requestUrl).expand(templateVars)));
              return restTemplate.getForObject(requestUrl, LoincConversion.class, templateVars);
            });

    if (response == null) {
      throw new RuntimeException("LOINC conversion service returned empty result.");
    }

    if (response.getValue() != null && response.getUnit() != null && response.getLoinc() != null) {
      var quantity = new Quantity();
      quantity.setValue(response.getValue());
      quantity.setUnit(response.getUnit());
      quantity.setCode(response.getUnit());
      quantity.setSystem(input.getSystem());
      return Pair.of(quantity, response);
    }

    return null;
  }
}
