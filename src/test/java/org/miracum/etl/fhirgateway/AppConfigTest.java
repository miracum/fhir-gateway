package org.miracum.etl.fhirgateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ca.uhn.fhir.rest.client.exceptions.FhirClientConnectionException;
import ca.uhn.fhir.rest.server.exceptions.InternalErrorException;
import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;
import ca.uhn.fhir.rest.server.exceptions.UnclassifiedServerFailureException;
import ca.uhn.fhir.rest.server.exceptions.UnprocessableEntityException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.retry.backoff.NoBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;

class AppConfigTest {

  @ParameterizedTest
  @ValueSource(ints = {502, 503, 504})
  void restRetryClassifier_withTransientStatusCode_isRetryable(int statusCode) {
    var classifier = AppConfig.restRetryClassifier();

    assertThat(
            classifier.classify(new UnclassifiedServerFailureException(statusCode, "unavailable")))
        .isTrue();
  }

  @Test
  void restRetryClassifier_withUnprocessableEntity_isNotRetryable() {
    var classifier = AppConfig.restRetryClassifier();

    assertThat(classifier.classify(new UnprocessableEntityException("value rejected"))).isFalse();
  }

  @Test
  void restRetryClassifier_withBadRequest_isNotRetryable() {
    var classifier = AppConfig.restRetryClassifier();

    assertThat(classifier.classify(new InvalidRequestException("malformed"))).isFalse();
  }

  @Test
  void restRetryClassifier_withOtherUnclassifiedClientError_isNotRetryable() {
    var classifier = AppConfig.restRetryClassifier();

    assertThat(classifier.classify(new UnclassifiedServerFailureException(418, "teapot")))
        .isFalse();
  }

  @Test
  void restRetryClassifier_withInternalServerError_isRetryable() {
    var classifier = AppConfig.restRetryClassifier();

    assertThat(classifier.classify(new InternalErrorException("unexpected"))).isTrue();
  }

  @Test
  void restRetryClassifier_withConnectionFailure_isRetryable() {
    var classifier = AppConfig.restRetryClassifier();

    assertThat(classifier.classify(new FhirClientConnectionException("connection refused")))
        .isTrue();
  }

  @Test
  void retryTemplate_withUnprocessableEntity_givesUpAfterTheFirstAttempt() {
    var retryTemplate = createRetryTemplateWithoutBackOff();
    var attempts = new AtomicInteger();

    assertThatThrownBy(
            () ->
                retryTemplate.execute(
                    ctx -> {
                      attempts.incrementAndGet();
                      throw new UnprocessableEntityException("value rejected");
                    }))
        .isInstanceOf(UnprocessableEntityException.class);

    assertThat(attempts).hasValue(1);
  }

  @Test
  void retryTemplate_withServiceUnavailable_retriesUntilItSucceeds() {
    var retryTemplate = createRetryTemplateWithoutBackOff();
    var attempts = new AtomicInteger();

    var result =
        retryTemplate.execute(
            ctx -> {
              if (attempts.incrementAndGet() < 3) {
                throw new UnclassifiedServerFailureException(503, "unavailable");
              }
              return "done";
            });

    assertThat(result).isEqualTo("done");
    assertThat(attempts).hasValue(3);
  }

  private static RetryTemplate createRetryTemplateWithoutBackOff() {
    var retryTemplate = new RetryTemplate();
    retryTemplate.setBackOffPolicy(new NoBackOffPolicy());
    retryTemplate.setRetryPolicy(new SimpleRetryPolicy(5, AppConfig.restRetryClassifier()));
    return retryTemplate;
  }
}
