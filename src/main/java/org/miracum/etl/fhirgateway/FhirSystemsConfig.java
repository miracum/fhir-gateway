package org.miracum.etl.fhirgateway;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "fhir.systems")
public record FhirSystemsConfig(@Nullable String loinc) {}
