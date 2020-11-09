package org.miracum.etl.fhirgateway;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.okhttp.client.OkHttpRestfulClientFactory;
import ca.uhn.fhir.rest.client.exceptions.FhirClientConnectionException;
import io.jaegertracing.internal.propagation.TraceContextCodec;
import io.opentracing.Span;
import io.opentracing.contrib.java.spring.jaeger.starter.TracerBuilderCustomizer;
import io.opentracing.contrib.okhttp3.OkHttpClientSpanDecorator;
import io.opentracing.contrib.okhttp3.TracingInterceptor;
import io.opentracing.propagation.Format;
import io.opentracing.util.GlobalTracer;
import java.util.Arrays;
import java.util.HashMap;
import okhttp3.Connection;
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
import org.springframework.retry.backoff.ExponentialBackOffPolicy;
import org.springframework.retry.listener.RetryListenerSupport;
import org.springframework.retry.policy.AlwaysRetryPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

@Configuration
public class AppConfig {
  private static final Logger LOG = LoggerFactory.getLogger(AppConfig.class);

  @Bean
  public FhirContext fhirContext() {
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

    var tracingInterceptor =
        new TracingInterceptor(
            GlobalTracer.get(),
            Arrays.asList(OkHttpClientSpanDecorator.STANDARD_TAGS, opNameDecorator));

    var okclient =
        new OkHttpClient.Builder()
            .addInterceptor(tracingInterceptor)
            .addNetworkInterceptor(tracingInterceptor)
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

    var backOffPolicy = new ExponentialBackOffPolicy();
    backOffPolicy.setInitialInterval(5000);
    backOffPolicy.setMultiplier(1.25);
    retryTemplate.setBackOffPolicy(backOffPolicy);

    var retryableExceptions = new HashMap<Class<? extends Throwable>, Boolean>();
    retryableExceptions.put(HttpClientErrorException.class, false);
    retryableExceptions.put(HttpServerErrorException.class, true);
    retryableExceptions.put(ResourceAccessException.class, true);
    retryableExceptions.put(FhirClientConnectionException.class, true);

    var retryPolicy =
        isKafkaEnabled ? new AlwaysRetryPolicy() : new SimpleRetryPolicy(5, retryableExceptions);

    retryTemplate.setRetryPolicy(retryPolicy);

    retryTemplate.registerListener(
        new RetryListenerSupport() {
          @Override
          public <T, E extends Throwable> void onError(
              RetryContext context, RetryCallback<T, E> callback, Throwable throwable) {
            LOG.warn(
                "HTTP Error occurred: {}. Retrying {}. attempt.",
                throwable.getMessage(),
                context.getRetryCount());
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
