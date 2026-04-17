package org.miracum.etl.fhirgateway.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "services.pseudonymizer.client-timeouts")
public record FhirClientTimeoutConfig(Duration call, Duration connect, Duration read) {}
