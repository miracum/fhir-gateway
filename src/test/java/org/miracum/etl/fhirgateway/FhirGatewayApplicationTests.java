package org.miracum.etl.fhirgateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import javax.sql.DataSource;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.miracum.etl.fhirgateway.config.FhirClientTimeoutConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

class FhirGatewayApplicationTests {

  @Nested
  @SpringBootTest
  @ActiveProfiles("test")
  class WithDefaults {
    @Autowired private ApplicationContext context;

    @Test
    void contextLoads_withoutADatabase() {
      assertThat(context.getBeansOfType(DataSource.class)).isEmpty();
    }

    @Test
    void fhirClientTimeouts_defaultToTwoMinutes() {
      assertThat(context.getBean(FhirClientTimeoutConfig.class))
          .isEqualTo(
              new FhirClientTimeoutConfig(
                  Duration.ofMinutes(2), Duration.ofMinutes(2), Duration.ofMinutes(2)));
    }
  }

  @Nested
  @SpringBootTest(properties = "services.pseudonymizer.client-timeouts.call=7s")
  @ActiveProfiles("test")
  class WithLegacyTimeoutSettings {
    @Autowired private FhirClientTimeoutConfig timeoutConfig;

    @Test
    void fhirClientTimeouts_fallBackToThePseudonymizerSettings() {
      assertThat(timeoutConfig.call()).isEqualTo(Duration.ofSeconds(7));
      assertThat(timeoutConfig.read()).isEqualTo(Duration.ofMinutes(2));
    }
  }

  @Nested
  @SpringBootTest(
      properties = {
        "services.psql.enabled=true",
        "spring.datasource.url=jdbc:postgresql://localhost:1/fhir",
        "spring.datasource.hikari.maximum-pool-size=3",
        // there is no database to initialize the schema in or to detect the dialect from
        "spring.sql.init.mode=never",
        "spring.data.jdbc.dialect=postgresql",
      })
  @ActiveProfiles("test")
  class WithPostgresEnabled {
    @Autowired private DataSource dataSource;

    @Test
    void dataSource_isConfiguredFromTheDataSourceProperties() {
      assertThat(dataSource)
          .isInstanceOfSatisfying(
              com.zaxxer.hikari.HikariDataSource.class,
              hikari -> {
                assertThat(hikari.getJdbcUrl()).isEqualTo("jdbc:postgresql://localhost:1/fhir");
                assertThat(hikari.getMaximumPoolSize()).isEqualTo(3);
              });
    }
  }
}
