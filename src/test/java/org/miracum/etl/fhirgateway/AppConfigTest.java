package org.miracum.etl.fhirgateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ca.uhn.fhir.rest.client.exceptions.FhirClientConnectionException;
import ca.uhn.fhir.rest.server.exceptions.InternalErrorException;
import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;
import ca.uhn.fhir.rest.server.exceptions.UnclassifiedServerFailureException;
import ca.uhn.fhir.rest.server.exceptions.UnprocessableEntityException;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.retry.backoff.ExponentialRandomBackOffPolicy;
import org.springframework.retry.backoff.NoBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.test.util.ReflectionTestUtils;

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

  @Test
  void retryTemplate_withServiceUnavailable_givesUpAfterFiveAttempts() {
    var retryTemplate = new AppConfig().retryTemplate();
    retryTemplate.setBackOffPolicy(new NoBackOffPolicy());
    var attempts = new AtomicInteger();

    assertThatThrownBy(
            () ->
                retryTemplate.execute(
                    ctx -> {
                      attempts.incrementAndGet();
                      throw new UnclassifiedServerFailureException(503, "unavailable");
                    }))
        .isInstanceOf(UnclassifiedServerFailureException.class);

    assertThat(attempts).hasValue(5);
  }

  @Test
  void databaseRetryClassifier_withConnectionFailure_isRetryable() {
    var classifier = AppConfig.databaseRetryClassifier();

    assertThat(classifier.classify(new CannotGetJdbcConnectionException("connection refused")))
        .isTrue();
  }

  @Test
  void databaseRetryClassifier_withQueryTimeout_isRetryable() {
    var classifier = AppConfig.databaseRetryClassifier();

    assertThat(classifier.classify(new QueryTimeoutException("canceling statement"))).isTrue();
  }

  @Test
  void databaseRetryClassifier_withConstraintViolation_isNotRetryable() {
    var classifier = AppConfig.databaseRetryClassifier();

    assertThat(classifier.classify(new DataIntegrityViolationException("value too long")))
        .isFalse();
  }

  @Test
  void databaseRetryClassifier_withBadSqlGrammar_isNotRetryable() {
    var classifier = AppConfig.databaseRetryClassifier();

    assertThat(
            classifier.classify(
                new BadSqlGrammarException("update", "UPDATE", new SQLException("syntax error"))))
        .isFalse();
  }

  @Test
  void databaseRetryTemplate_backsOffBetweenAttempts() {
    var retryTemplate = new AppConfig().databaseRetryTemplate();

    assertThat(ReflectionTestUtils.getField(retryTemplate, "backOffPolicy"))
        .isInstanceOf(ExponentialRandomBackOffPolicy.class);
  }

  @Test
  void databaseRetryTemplate_withConstraintViolation_givesUpAfterTheFirstAttempt() {
    var retryTemplate = new AppConfig().databaseRetryTemplate();
    var attempts = new AtomicInteger();

    assertThatThrownBy(
            () ->
                retryTemplate.execute(
                    ctx -> {
                      attempts.incrementAndGet();
                      throw new DataIntegrityViolationException("value too long");
                    }))
        .isInstanceOf(DataIntegrityViolationException.class);

    assertThat(attempts).hasValue(1);
  }

  @Test
  void isTransientFailure_withTransientRestOrDatabaseFailure_isTrue() {
    assertThat(AppConfig.isTransientFailure(new UnclassifiedServerFailureException(503, "x")))
        .isTrue();
    assertThat(AppConfig.isTransientFailure(new CannotGetJdbcConnectionException("x"))).isTrue();
  }

  @Test
  void isTransientFailure_withPermanentFailure_isFalse() {
    assertThat(AppConfig.isTransientFailure(new UnprocessableEntityException("x"))).isFalse();
    assertThat(AppConfig.isTransientFailure(new DataIntegrityViolationException("x"))).isFalse();
    assertThat(AppConfig.isTransientFailure(new NullPointerException())).isFalse();
  }

  private static RetryTemplate createRetryTemplateWithoutBackOff() {
    var retryTemplate = new RetryTemplate();
    retryTemplate.setBackOffPolicy(new NoBackOffPolicy());
    retryTemplate.setRetryPolicy(new SimpleRetryPolicy(5, AppConfig.restRetryClassifier()));
    return retryTemplate;
  }
}
