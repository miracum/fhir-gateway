package org.miracum.etl.fhirgateway.processors;

import org.hl7.fhir.r4.model.Observation;
import org.miracum.etl.fhirgateway.controllers.FhirController;
import org.miracum.etl.fhirgateway.models.loinc.LoincConversion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.util.List;

@Service
public class LoincHarmonizer {
    private static final Logger log = LoggerFactory.getLogger(FhirController.class);

    private final RestTemplate restTemplate;
    private final URI loincConverterUri;
    private RetryTemplate retryTemplate;

    public LoincHarmonizer(RestTemplate restTemplate, @Value("${services.loinc.conversions.url}") URI loincConverterUri, RetryTemplate retryTemplate) {
        this.restTemplate = restTemplate;
        this.loincConverterUri = loincConverterUri;
        this.retryTemplate = retryTemplate;
    }

    public Observation process(Observation observation) throws Exception {
        var loincCode = observation.getCode()
                .getCoding()
                .stream()
                .filter(obs -> obs.getSystem().equals("http://loinc.org"))
                .findFirst();

        if (loincCode.isPresent() && observation.hasValueQuantity()) {
            var conversionRequest = new LoincConversion() {{
                setId(observation.getId());
                setLoinc(loincCode.orElse(null).getCode());
                setValue(observation.getValueQuantity().getValue());
                setUnit(observation.getValueQuantity().getUnit());
            }};

            var response = retryTemplate.execute(ctx -> restTemplate.postForObject(loincConverterUri,
                    List.of(conversionRequest),
                    LoincConversion[].class));

            if (response.length == 0) {
                throw new Exception("LOINC conversion service returned empty result.");
            }

            var conversionResult = response[0];

            observation.getValueQuantity().setValue(conversionResult.getValue());
            observation.getValueQuantity().setUnit(conversionResult.getUnit());
            observation.getValueQuantity().setCode(conversionResult.getUnit());
            observation.getCode().getCodingFirstRep().setCode(conversionResult.getLoinc());
        }

        return observation;
    }
}
