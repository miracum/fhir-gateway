package org.miracum.etl.fhirgateway.processors;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Metrics;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Quantity;
import org.miracum.etl.fhirgateway.controllers.FhirController;
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

import java.net.URI;
import java.util.HashMap;

@Service
public class LoincHarmonizer {
    private static final Logger log = LoggerFactory.getLogger(FhirController.class);

    private static final String CONVERSION_ERROR_METRIC_NAME = "fhirgateway.loinc.conversion.errors.total";
    private static final HashMap<String, Counter> metricsLookup = new HashMap<>();

    private final RestTemplate restTemplate;
    private final URI loincConverterUri;
    private RetryTemplate retryTemplate;

    public LoincHarmonizer(RestTemplate restTemplate,
                           @Value("${services.loinc.conversions.url}") URI loincConverterUri) {
        this.restTemplate = restTemplate;
        this.loincConverterUri = loincConverterUri;
        this.retryTemplate = new RetryTemplate();

        var fixedBackOffPolicy = new FixedBackOffPolicy();
        fixedBackOffPolicy.setBackOffPeriod(5_000);
        retryTemplate.setBackOffPolicy(fixedBackOffPolicy);

        var retryableExceptions = new HashMap<Class<? extends Throwable>, Boolean>() {{
            put(HttpClientErrorException.class, false);
            put(HttpServerErrorException.class, true);
        }};

        retryTemplate.setRetryPolicy(new SimpleRetryPolicy(5, retryableExceptions));
    }

    public Observation process(Observation observation) {
        var loincCode = observation.getCode().getCoding().stream()
                .filter(obs -> obs.getSystem().equals("http://loinc.org"))
                .findFirst();

        // only process observation resources with a set quantity and code
        if (loincCode.isPresent() && observation.hasValueQuantity() && observation.getValueQuantity().hasUnit()) {
            try {

                var originalCode = loincCode.get().getCode();
                // harmonize the observation's main code/value
                var result = getHarmonizedQuantity(observation.getValueQuantity(),
                        originalCode,
                        observation.getId());

                if (result != null) {
                    observation.setValue(result.getFirst());
                    observation.getCode().getCodingFirstRep().setCode(result.getSecond());

                    // if the LOINC code has changed, the display is most likely incorrect, just drop it
                    if (!originalCode.equals(result.getSecond())) {
                        observation.getCode().getCodingFirstRep().setDisplay(null);
                    }
                }

                // harmonize the reference range
                if (observation.hasReferenceRange()) {
                    for (var rangeComponent : observation.getReferenceRange()) {
                        if (rangeComponent.hasLow()) {
                            var rangeLow = rangeComponent.getLow();

                            result = getHarmonizedQuantity(rangeLow,
                                    originalCode,
                                    observation.getId() + "-refRange-low");

                            if (result != null) {
                                rangeComponent.setLow(result.getFirst());
                            }
                        }

                        if (rangeComponent.hasHigh()) {
                            var rangeHigh = rangeComponent.getHigh();

                            result = getHarmonizedQuantity(rangeHigh,
                                    originalCode,
                                    observation.getId() + "-refRange-high");

                            if (result != null) {
                                rangeComponent.setHigh(result.getFirst());
                            }
                        }
                    }
                }

            } catch (Exception exc) {
                log.debug("LOINC Conversion failure for observation {} (loinc={}; unit={}).",
                        observation.getId(),
                        loincCode.orElse(null).getCode(),
                        observation.getValueQuantity().getUnit(),
                        exc);

                var unit = observation.getValueQuantity().getUnit();
                metricsLookup.putIfAbsent(unit,
                        Metrics.globalRegistry.counter(CONVERSION_ERROR_METRIC_NAME, "unit", unit));
                metricsLookup.get(unit).increment();
                return observation;
            }
        }

        return observation;
    }

    private Pair<Quantity, String> getHarmonizedQuantity(Quantity input, String loincCode, String id) {
        var conversionRequest = new LoincConversion()
                .setId(id)
                .setLoinc(loincCode)
                .setValue(input.getValue())
                .setUnit(input.getUnit());

        var response = retryTemplate.execute(ctx ->
                restTemplate.postForObject(loincConverterUri, conversionRequest, LoincConversion.class));

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
