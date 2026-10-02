package org.miracum.etl.fhirgateway.stores;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourcePoolMetadataProvidersConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Creates the DataSource from the spring.datasource.* properties, but only if storing resources in
 * PostgreSQL is enabled. Spring Boot's own DataSourceAutoConfiguration is excluded, as it would
 * otherwise always create one, which then also runs the schema initialization at startup.
 */
@Configuration
@ConditionalOnExpression("${services.psql.enabled}")
@EnableConfigurationProperties(DataSourceProperties.class)
@Import(DataSourcePoolMetadataProvidersConfiguration.class)
public class PostgresConfig {

  @Bean
  @ConfigurationProperties("spring.datasource.hikari")
  HikariDataSource dataSource(DataSourceProperties properties) {
    return properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
  }
}
