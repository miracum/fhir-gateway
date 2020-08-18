package org.miracum.etl.fhirgateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.transaction.annotation.EnableTransactionManagement;

@SpringBootApplication
@EnableTransactionManagement
public class FhirGatewayApplication {

  public static void main(String[] args) {
    SpringApplication.run(FhirGatewayApplication.class, args);
  }
}
