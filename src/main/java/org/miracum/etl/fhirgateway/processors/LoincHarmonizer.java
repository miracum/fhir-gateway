package org.miracum.etl.fhirgateway.processors;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Metrics;
import org.hl7.fhir.r4.model.Observation;
import org.miracum.etl.fhirgateway.controllers.FhirController;
import org.miracum.etl.fhirgateway.models.loinc.LoincConversion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.util.HashMap;
import java.util.List;

@Service
public class LoincHarmonizer {
    private static final Logger log = LoggerFactory.getLogger(FhirController.class);

    private static final String CONVERSION_ERROR_METRIC_NAME = "fhirgateway.loinc.conversion.errors.total";
    private static final HashMap<String, Counter> metricsLookup = new HashMap<>();

    private final RestTemplate restTemplate;
    private final URI loincConverterUri;
    private RetryTemplate retryTemplate;

    public LoincHarmonizer(
            RestTemplate restTemplate,
            @Value("${services.loinc.conversions.url}") URI loincConverterUri,
            RetryTemplate retryTemplate) {
        this.restTemplate = restTemplate;
        this.loincConverterUri = loincConverterUri;
        this.retryTemplate = retryTemplate;

        var retryableExceptions = new HashMap<Class<? extends Throwable>, Boolean>() {{
            put(HttpClientErrorException.class, false);
            put(HttpServerErrorException.class, true);
        }};

        this.retryTemplate.setRetryPolicy(new SimpleRetryPolicy(5, retryableExceptions));
    }

    public Observation process(Observation observation) {
        var loincCode = observation.getCode().getCoding().stream()
                .filter(obs -> obs.getSystem().equals("http://loinc.org"))
                .findFirst();

        // only process observation resources with a set quantity and code
        if (loincCode.isPresent() && observation.hasValueQuantity() && observation.getValueQuantity().hasUnit()) {
            var conversionRequest = new LoincConversion() {
                {
                    setId(observation.getId());
                    setLoinc(loincCode.orElse(null).getCode());
                    setValue(observation.getValueQuantity().getValue());
                    setUnit(observation.getValueQuantity().getUnit());
                }
            };

            try {
                var response = retryTemplate.execute(ctx ->
                        restTemplate.postForObject(loincConverterUri, List.of(conversionRequest), LoincConversion[].class));

                if (response.length == 0) {
                    throw new RuntimeException("LOINC conversion service returned empty result.");
                }

                var conversionResult = response[0];

                // make sure the conversion returned a valid value, unit, and code before overriding the resource
                if (conversionResult.getValue() != null && conversionResult.getUnit() != null && conversionResult.getLoinc() != null) {
                    observation.getValueQuantity().setValue(conversionResult.getValue());
                    observation.getValueQuantity().setUnit(conversionResult.getUnit());
                    observation.getValueQuantity().setCode(conversionResult.getUnit());
                    observation.getCode().getCodingFirstRep().setCode(conversionResult.getLoinc());
                }
            } catch (Exception exc) {
                log.warn("LOINC Conversion failure for observation {} (loinc={}; unit={}).",
                        observation.getId(),
                        loincCode.orElse(null).getCode(),
                        observation.getValueQuantity().getUnit());

                var unit = observation.getValueQuantity().getUnit();
                metricsLookup.putIfAbsent(unit,
                        Metrics.globalRegistry.counter(CONVERSION_ERROR_METRIC_NAME, "unit", unit));
                metricsLookup.get(unit).increment();
                return observation;
            }
        }

        return observation;
    }
}
