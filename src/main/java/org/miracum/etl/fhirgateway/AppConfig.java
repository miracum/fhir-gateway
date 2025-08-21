package org.miracum.etl.fhirgateway;

import static net.logstash.logback.argument.StructuredArguments.kv;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.okhttp.client.OkHttpRestfulClientFactory;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.client.exceptions.FhirClientConnectionException;
import ca.uhn.fhir.rest.client.interceptor.BasicAuthInterceptor;
import ca.uhn.fhir.rest.server.exceptions.InternalErrorException;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;
import ca.uhn.fhir.rest.server.exceptions.ResourceVersionConflictException;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.binder.okhttp3.OkHttpMetricsEventListener;
import java.util.HashMap;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.ConnectionPool;
import okhttp3.OkHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.retry.RetryCallback;
import org.springframework.retry.RetryContext;
import org.springframework.retry.RetryListener;
import org.springframework.retry.backoff.ExponentialRandomBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

@Configuration
public class AppConfig {
  private static final Logger LOG = LoggerFactory.getLogger(AppConfig.class);

  private static final int MAX_IDLE_CONNECTIONS = 2;
  private static final int KEEP_ALIVE_DURATION_MILLISECONDS = 100;

  private static final AtomicInteger batchUpdateFailed =
      Metrics.globalRegistry.gauge(
          "fhirgateway.postgres.batchupdate.errors.total", new AtomicInteger(0));

  @Bean
  public FhirContext fhirContext(
      @Value("${features.use-load-balancer-optimized-connection-pool}")
          boolean useLoadBalancerConnectionPool) {
    var fhirContext = FhirContext.forR4();

    var connectionPool = new ConnectionPool();
    if (useLoadBalancerConnectionPool) {
      connectionPool =
          new ConnectionPool(
              MAX_IDLE_CONNECTIONS, KEEP_ALIVE_DURATION_MILLISECONDS, TimeUnit.MILLISECONDS);
    }

    var okclient =
        new OkHttpClient.Builder()
            .connectionPool(connectionPool)
            .eventListener(
                OkHttpMetricsEventListener.builder(Metrics.globalRegistry, "fhir.client").build())
            .build();

    var okHttpFactory = new OkHttpRestfulClientFactory(fhirContext);
    okHttpFactory.setHttpClient(okclient);

    fhirContext.setRestfulClientFactory(okHttpFactory);
    return fhirContext;
  }

  @Bean
  IGenericClient fhirClient(
      FhirContext fhirContext,
      @Value("${services.fhirServer.auth.basic.username}") String username,
      @Value("${services.fhirServer.auth.basic.password}") String password,
      @Value("${services.fhirServer.auth.basic.enabled}") boolean isBasicAuthEnabled,
      @Value("${services.fhirServer.url}") String fhirServerUrl) {
    var client = fhirContext.newRestfulGenericClient(fhirServerUrl);

    if (isBasicAuthEnabled) {
      client.registerInterceptor(new BasicAuthInterceptor(username, password));
    }

    return client;
  }

  @Bean
  public RestTemplate restTemplate(RestTemplateBuilder builder) {
    return builder.build();
  }

  @Bean
  @Primary
  @Qualifier("restRetryTemplate")
  public RetryTemplate retryTemplate(@Value("${services.kafka.enabled}") boolean isKafkaEnabled) {
    var retryTemplate = new RetryTemplate();

    var backOffPolicy = new ExponentialRandomBackOffPolicy();
    backOffPolicy.setInitialInterval(5_000); // 5 seconds
    backOffPolicy.setMaxInterval(300_000); // 5 minutes

    retryTemplate.setBackOffPolicy(backOffPolicy);

    var retryableExceptions = new HashMap<Class<? extends Throwable>, Boolean>();
    retryableExceptions.put(HttpClientErrorException.class, false);
    retryableExceptions.put(HttpServerErrorException.class, true);
    retryableExceptions.put(ResourceAccessException.class, true);
    retryableExceptions.put(FhirClientConnectionException.class, true);
    retryableExceptions.put(ResourceNotFoundException.class, false);
    retryableExceptions.put(ResourceVersionConflictException.class, false);
    retryableExceptions.put(InternalErrorException.class, true);

    var maxAttempts = isKafkaEnabled ? Integer.MAX_VALUE : 5;

    retryTemplate.setRetryPolicy(new SimpleRetryPolicy(maxAttempts, retryableExceptions));

    retryTemplate.registerListener(
        new RetryListener() {
          @Override
          public <T, E extends Throwable> void onError(
              RetryContext context, RetryCallback<T, E> callback, Throwable throwable) {
            LOG.warn(
                "HTTP Error occurred: {}. Retrying {} out of {}",
                throwable.getMessage(),
                kv("attempt", context.getRetryCount()),
                kv("maxAttempts", maxAttempts));
          }
        });

    return retryTemplate;
  }

  @Bean
  @Qualifier("databaseRetryTemplate")
  @ConditionalOnProperty("services.psql.enabled")
  public RetryTemplate databaseRetryTemplate(
      @Value("${services.kafka.enabled}") boolean isKafkaEnabled) {
    var retryTemplate = new RetryTemplate();

    var backOffPolicy = new ExponentialRandomBackOffPolicy();
    backOffPolicy.setInitialInterval(5_000); // 5 seconds
    backOffPolicy.setMaxInterval(300_000); // 5 minutes

    var maxAttempts = isKafkaEnabled ? Integer.MAX_VALUE : 5;

    retryTemplate.setRetryPolicy(new SimpleRetryPolicy(maxAttempts));

    retryTemplate.registerListener(
        new RetryListener() {
          @Override
          public <T, E extends Throwable> void onError(
              RetryContext context, RetryCallback<T, E> callback, Throwable throwable) {
            LOG.warn(
                "Database Error occurred: {}. Retrying {} out of {}",
                throwable.getMessage(),
                kv("attempt", context.getRetryCount()),
                kv("maxAttempts", maxAttempts));

            Objects.requireNonNull(batchUpdateFailed).incrementAndGet();
          }
        });

    return retryTemplate;
  }

  @Bean
  @Qualifier("kafkaRetryTemplate")
  @ConditionalOnProperty("services.kafka.produce-from-api.enabled")
  public RetryTemplate kafkaRetryTemplate() {
    var retryTemplate = new RetryTemplate();

    var backOffPolicy = new ExponentialRandomBackOffPolicy();
    backOffPolicy.setInitialInterval(5_000); // 5 seconds
    backOffPolicy.setMaxInterval(300_000); // 5 minutes

    retryTemplate.setBackOffPolicy(backOffPolicy);

    var retryableExceptions = new HashMap<Class<? extends Throwable>, Boolean>();

    var maxAttempts = Integer.MAX_VALUE;
    retryTemplate.setRetryPolicy(new SimpleRetryPolicy(maxAttempts, retryableExceptions));

    retryTemplate.registerListener(
        new RetryListener() {
          @Override
          public <T, E extends Throwable> void onError(
              RetryContext context, RetryCallback<T, E> callback, Throwable throwable) {
            LOG.warn(
                "Error occurred when producing to Kafka: {}. Retrying {} out of {}",
                throwable.getMessage(),
                kv("attempt", context.getRetryCount()),
                kv("maxAttempts", maxAttempts));
          }
        });

    return retryTemplate;
  }
}
