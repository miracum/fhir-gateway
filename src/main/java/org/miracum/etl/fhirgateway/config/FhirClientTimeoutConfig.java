package org.miracum.etl.fhirgateway.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Timeouts of all requests to FHIR servers, i.e. to both the pseudonymizer and the FHIR server. */
@ConfigurationProperties(prefix = "fhir.client.timeouts")
public record FhirClientTimeoutConfig(Duration call, Duration connect, Duration read) {}
