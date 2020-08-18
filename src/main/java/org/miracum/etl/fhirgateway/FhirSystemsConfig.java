package org.miracum.etl.fhirgateway;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "fhir.systems")
public class FhirSystemsConfig {

  private String loinc;

  public String getLoinc() {
    return loinc;
  }

  public FhirSystemsConfig setLoinc(String loinc) {
    this.loinc = loinc;
    return this;
  }
}
