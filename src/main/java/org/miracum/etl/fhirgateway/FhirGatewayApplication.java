package org.miracum.etl.fhirgateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;

// the DataSource is only created if storing resources in PostgreSQL is enabled, see PostgresConfig
@SpringBootApplication(exclude = DataSourceAutoConfiguration.class)
@ConfigurationPropertiesScan
public class FhirGatewayApplication {

  public static void main(String[] args) {
    SpringApplication.run(FhirGatewayApplication.class, args);
  }
}
