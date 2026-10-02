package org.miracum.etl.fhirgateway;

import static net.logstash.logback.argument.StructuredArguments.kv;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.okhttp.client.OkHttpRestfulClientFactory;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.client.exceptions.FhirClientConnectionException;
import ca.uhn.fhir.rest.client.interceptor.BasicAuthInterceptor;
import ca.uhn.fhir.rest.server.exceptions.BaseServerResponseException;
import ca.uhn.fhir.rest.server.exceptions.InternalErrorException;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;
import ca.uhn.fhir.rest.server.exceptions.ResourceVersionConflictException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.binder.okhttp3.OkHttpMetricsEventListener;
import java.io.IOException;
import java.util.HashMap;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import okhttp3.ConnectionPool;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.BufferedSink;
import okio.GzipSink;
import okio.Okio;
import org.miracum.etl.fhirgateway.config.FhirClientTimeoutConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.classify.BinaryExceptionClassifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
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

  private static final Set<Integer> TRANSIENT_HTTP_STATUS_CODES = Set.of(502, 503, 504);

  /**
   * Retries block the calling thread - for records consumed from Kafka, the consumer thread - so
   * they are bounded (to at most ~150s of back off) to stay within the consumer's
   * max.poll.interval.ms. Longer outages are ridden out by the Kafka listener container instead,
   * see {@link org.miracum.etl.fhirgateway.processors.KafkaErrorHandlerConfig}.
   */
  private static final int MAX_ATTEMPTS = 5;

  private static final BinaryExceptionClassifier REST_RETRY_CLASSIFIER = restRetryClassifier();
  private static final BinaryExceptionClassifier DATABASE_RETRY_CLASSIFIER =
      databaseRetryClassifier();

  private static final Counter BATCH_UPDATE_FAILED_COUNTER =
      Counter.builder("fhirgateway.postgres.batchupdate.errors")
          .description("Number of failed attempts to store a FHIR bundle in the database")
          .register(Metrics.globalRegistry);

  @Bean
  FhirContext fhirContext(
      @Value("${features.use-load-balancer-optimized-connection-pool}")
          boolean useLoadBalancerConnectionPool,
      @Value("${features.use-fhir-client-request-compression}") boolean useRequestCompression,
      FhirClientTimeoutConfig timeoutConfig) {
    var fhirContext = FhirContext.forR4();

    var connectionPool = new ConnectionPool();
    if (useLoadBalancerConnectionPool) {
      connectionPool =
          new ConnectionPool(
              MAX_IDLE_CONNECTIONS, KEEP_ALIVE_DURATION_MILLISECONDS, TimeUnit.MILLISECONDS);
    }

    var clientBuilder =
        new OkHttpClient.Builder()
            .connectionPool(connectionPool)
            .callTimeout(timeoutConfig.call())
            .readTimeout(timeoutConfig.read())
            .connectTimeout(timeoutConfig.connect())
            .eventListener(
                OkHttpMetricsEventListener.builder(Metrics.globalRegistry, "fhir.client").build());

    if (useRequestCompression) {
      clientBuilder.addInterceptor(new GzipRequestInterceptor());
    }

    var okHttpFactory = new OkHttpRestfulClientFactory(fhirContext);
    okHttpFactory.setHttpClient(clientBuilder.build());

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
  public RetryTemplate retryTemplate() {
    var retryTemplate = new RetryTemplate();
    retryTemplate.setBackOffPolicy(createBackOffPolicy());
    retryTemplate.setRetryPolicy(new SimpleRetryPolicy(MAX_ATTEMPTS, REST_RETRY_CLASSIFIER));

    retryTemplate.registerListener(
        new RetryListener() {
          @Override
          public <T, E extends Throwable> void onError(
              RetryContext context, RetryCallback<T, E> callback, Throwable throwable) {
            LOG.warn(
                "HTTP Error occurred: {}. Retrying {} out of {}",
                throwable.getMessage(),
                kv("attempt", context.getRetryCount()),
                kv("maxAttempts", MAX_ATTEMPTS));
          }
        });

    return retryTemplate;
  }

  /**
   * Which failures of a REST call (to the FHIR server, the pseudonymizer, or the LOINC conversion
   * service) are worth retrying. Anything not retried - e.g. a 422 from the pseudonymizer for a
   * value its pseudonymization service rejects - propagates, so the message ends up in the dead
   * letter topic instead of blocking its partition.
   */
  static BinaryExceptionClassifier restRetryClassifier() {
    var retryableExceptions = new HashMap<Class<? extends Throwable>, Boolean>();
    retryableExceptions.put(HttpClientErrorException.class, false);
    retryableExceptions.put(HttpServerErrorException.class, true);
    retryableExceptions.put(ResourceAccessException.class, true);
    retryableExceptions.put(FhirClientConnectionException.class, true);
    retryableExceptions.put(ResourceNotFoundException.class, false);
    retryableExceptions.put(ResourceVersionConflictException.class, false);
    retryableExceptions.put(InternalErrorException.class, true);

    return new BinaryExceptionClassifier(retryableExceptions, false) {
      @Override
      public Boolean classify(Throwable throwable) {
        // HAPI has no dedicated exception for these, they surface as an
        // UnclassifiedServerFailureException, so are told apart by their status code instead.
        if (throwable instanceof BaseServerResponseException exc
            && TRANSIENT_HTTP_STATUS_CODES.contains(exc.getStatusCode())) {
          return true;
        }
        return super.classify(throwable);
      }
    };
  }

  /**
   * Which failures of a database operation are worth retrying. Anything else - e.g. a constraint
   * violation - fails the same way on every attempt, so propagates right away instead.
   */
  static BinaryExceptionClassifier databaseRetryClassifier() {
    var retryableExceptions = new HashMap<Class<? extends Throwable>, Boolean>();
    retryableExceptions.put(TransientDataAccessException.class, true);
    retryableExceptions.put(RecoverableDataAccessException.class, true);
    // includes failing to get a connection at all, e.g. because the database is down
    retryableExceptions.put(DataAccessResourceFailureException.class, true);

    return new BinaryExceptionClassifier(retryableExceptions, false);
  }

  /**
   * Whether a failure is expected to go away on its own, e.g. because a downstream service is
   * temporarily unavailable, as opposed to one that fails the same way no matter how often it is
   * retried.
   */
  public static boolean isTransientFailure(Throwable throwable) {
    return REST_RETRY_CLASSIFIER.classify(throwable)
        || DATABASE_RETRY_CLASSIFIER.classify(throwable);
  }

  @Bean
  @Qualifier("databaseRetryTemplate")
  @ConditionalOnExpression("${services.psql.enabled}")
  public RetryTemplate databaseRetryTemplate() {
    var retryTemplate = new RetryTemplate();
    retryTemplate.setBackOffPolicy(createBackOffPolicy());
    retryTemplate.setRetryPolicy(new SimpleRetryPolicy(MAX_ATTEMPTS, DATABASE_RETRY_CLASSIFIER));

    retryTemplate.registerListener(
        new RetryListener() {
          @Override
          public <T, E extends Throwable> void onError(
              RetryContext context, RetryCallback<T, E> callback, Throwable throwable) {
            LOG.warn(
                "Database Error occurred: {}. Retrying {} out of {}",
                throwable.getMessage(),
                kv("attempt", context.getRetryCount()),
                kv("maxAttempts", MAX_ATTEMPTS));

            BATCH_UPDATE_FAILED_COUNTER.increment();
          }
        });

    return retryTemplate;
  }

  private static ExponentialRandomBackOffPolicy createBackOffPolicy() {
    var backOffPolicy = new ExponentialRandomBackOffPolicy();
    backOffPolicy.setInitialInterval(5_000); // 5 seconds
    backOffPolicy.setMaxInterval(300_000); // 5 minutes
    return backOffPolicy;
  }

  // <https://github.com/square/okhttp/blob/master/samples/guide/src/main/java/okhttp3/recipes/RequestBodyCompression.java>
  static class GzipRequestInterceptor implements Interceptor {
    @Override
    public Response intercept(Chain chain) throws IOException {
      Request originalRequest = chain.request();
      if (originalRequest.body() == null || originalRequest.header("Content-Encoding") != null) {
        return chain.proceed(originalRequest);
      }

      Request compressedRequest =
          originalRequest
              .newBuilder()
              .header("Content-Encoding", "gzip")
              .method(originalRequest.method(), gzip(originalRequest.body()))
              .build();
      return chain.proceed(compressedRequest);
    }

    // <https://github.com/square/okhttp/commit/71a759f77e7dc31939954b5eeb70065f29ee59ad>
    private RequestBody gzip(final RequestBody body) {
      return new RequestBody() {
        @Override
        public MediaType contentType() {
          return body.contentType();
        }

        @Override
        public long contentLength() {
          return -1; // We don't know the compressed length in advance!
        }

        @Override
        public void writeTo(BufferedSink sink) throws IOException {
          BufferedSink gzipSink = Okio.buffer(new GzipSink(sink));
          body.writeTo(gzipSink);
          gzipSink.close();
        }
      };
    }
  }
}
