package org.miracum.etl.fhirgateway.processors;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "fhir.validation")
public record ValidationConfig(
    boolean enabled,
    Path packageBaseDirectoryPath,
    boolean failOnError,
    boolean anyExtensionAllowed,
    ConcurrentBundleValidation concurrentBundleValidation) {

  public record ConcurrentBundleValidation(boolean enabled, int maxThreadCount) {}
}
