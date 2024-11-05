package org.miracum.etl.fhirgateway;

import jakarta.annotation.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "fhir.systems")
public class FhirSystemsConfig {

  @Nullable private String loinc;

  @Nullable
  public String getLoinc() {
    return loinc;
  }

  public FhirSystemsConfig setLoinc(String loinc) {
    this.loinc = loinc;
    return this;
  }
}
