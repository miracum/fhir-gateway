package org.miracum.etl.fhirgateway;

import static net.logstash.logback.argument.StructuredArguments.kv;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.okhttp.client.OkHttpRestfulClientFactory;
import ca.uhn.fhir.rest.client.exceptions.FhirClientConnectionException;
import ca.uhn.fhir.rest.server.exceptions.InternalErrorException;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;
import ca.uhn.fhir.rest.server.exceptions.ResourceVersionConflictException;
import io.jaegertracing.internal.propagation.TraceContextCodec;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.binder.okhttp3.OkHttpMetricsEventListener;
import io.opentracing.Span;
import io.opentracing.contrib.java.spring.jaeger.starter.TracerBuilderCustomizer;
import io.opentracing.contrib.okhttp3.OkHttpClientSpanDecorator;
import io.opentracing.contrib.okhttp3.TracingInterceptor;
import io.opentracing.propagation.Format;
import io.opentracing.util.GlobalTracer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.concurrent.TimeUnit;
import okhttp3.Connection;
import okhttp3.ConnectionPool;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.miracum.etl.fhirgateway.processors.FhirPseudonymizer;
import org.miracum.etl.fhirgateway.processors.IPseudonymizer;
import org.miracum.etl.fhirgateway.processors.NoopPseudonymizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.RetryCallback;
import org.springframework.retry.RetryContext;
import org.springframework.retry.backoff.ExponentialRandomBackOffPolicy;
import org.springframework.retry.listener.RetryListenerSupport;
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

  @Bean
  public FhirContext fhirContext(
      @Value("${features.use-load-balancer-optimized-connection-pool}")
          boolean useLoadBalancerConnectionPool) {
    var fhirContext = FhirContext.forR4();

    var opNameDecorator =
        new OkHttpClientSpanDecorator() {
          @Override
          public void onRequest(Request request, Span span) {
            // add the operation name to the span
            span.setOperationName(request.url().encodedPath());
          }

          @Override
          public void onError(Throwable throwable, Span span) {}

          @Override
          public void onResponse(Connection connection, Response response, Span span) {}
        };

    var connectionPool = new ConnectionPool();
    if (useLoadBalancerConnectionPool) {
      connectionPool =
          new ConnectionPool(
              MAX_IDLE_CONNECTIONS, KEEP_ALIVE_DURATION_MILLISECONDS, TimeUnit.MILLISECONDS);
    }

    var tracingInterceptor =
        new TracingInterceptor(
            GlobalTracer.get(),
            Arrays.asList(OkHttpClientSpanDecorator.STANDARD_TAGS, opNameDecorator));

    var okclient =
        new OkHttpClient.Builder()
            .addInterceptor(tracingInterceptor)
            .addNetworkInterceptor(tracingInterceptor)
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
  public TracerBuilderCustomizer traceContextJaegerTracerCustomizer() {
    return builder -> {
      var injector = new TraceContextCodec.Builder().build();

      builder
          .registerInjector(Format.Builtin.HTTP_HEADERS, injector)
          .registerExtractor(Format.Builtin.HTTP_HEADERS, injector);

      builder
          .registerInjector(Format.Builtin.TEXT_MAP, injector)
          .registerExtractor(Format.Builtin.TEXT_MAP, injector);
    };
  }

  @Bean
  public RestTemplate restTemplate(RestTemplateBuilder builder) {
    return builder.build();
  }

  @Bean
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
        new RetryListenerSupport() {
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
  public RetryTemplate databaseRetryTemplate(
      @Value("${services.kafka.enabled}") boolean isKafkaEnabled) {
    var retryTemplate = new RetryTemplate();

    var backOffPolicy = new ExponentialRandomBackOffPolicy();
    backOffPolicy.setInitialInterval(5_000); // 5 seconds
    backOffPolicy.setMaxInterval(300_000); // 5 minutes

    var maxAttempts = isKafkaEnabled ? Integer.MAX_VALUE : 5;

    retryTemplate.setRetryPolicy(new SimpleRetryPolicy(maxAttempts));

    retryTemplate.registerListener(
        new RetryListenerSupport() {
          @Override
          public <T, E extends Throwable> void onError(
              RetryContext context, RetryCallback<T, E> callback, Throwable throwable) {
            LOG.warn(
                "Database Error occurred: {}. Retrying {} out of {}",
                throwable.getMessage(),
                kv("attempt", context.getRetryCount()),
                kv("maxAttempts", maxAttempts));
          }
        });

    return retryTemplate;
  }

  @Bean
  public IPseudonymizer fhirPseudonymizer(
      @Value("${services.pseudonymizer.enabled}") boolean isPseudonymizerEnabled,
      @Value("${services.pseudonymizer.url}") String pseudonymizerUrl,
      @Qualifier("restRetryTemplate") RetryTemplate retryTemplate,
      FhirContext fhirContext) {
    if (isPseudonymizerEnabled) {
      return new FhirPseudonymizer(fhirContext, pseudonymizerUrl, retryTemplate);
    } else {
      return new NoopPseudonymizer();
    }
  }

  @Bean
  @ConditionalOnProperty(
      value = "opentracing.jaeger.enabled",
      havingValue = "false",
      matchIfMissing = false)
  public io.opentracing.Tracer jaegerTracer() {
    return io.opentracing.noop.NoopTracerFactory.create();
  }
}
