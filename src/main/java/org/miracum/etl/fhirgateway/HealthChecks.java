package org.miracum.etl.fhirgateway;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.net.URI;

@Component
public class HealthChecks implements HealthIndicator {
    private final RestTemplate restTemplate;
    private final URI loincConverterUri;

    public HealthChecks(RestTemplate restTemplate,
                        @Value("${services.loinc.conversions.healthCheck}") URI loincConverterUri) {
        this.restTemplate = restTemplate;
        this.loincConverterUri = loincConverterUri;
    }

    @Override
    public Health health() {
        try {
            restTemplate.getForObject(loincConverterUri, Object.class);
        } catch (Exception e) {
            return Health.down()
                    .withDetail("LOINC Conversion Service", "Not available: " + e.getMessage())
                    .build();
        }
        return Health.up().withDetail("LOINC Conversion Service", "Available").build();
    }
}
