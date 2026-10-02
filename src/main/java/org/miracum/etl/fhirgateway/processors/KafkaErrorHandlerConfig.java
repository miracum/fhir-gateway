package org.miracum.etl.fhirgateway.processors;

import java.util.function.BiFunction;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.cloud.stream.binder.ExtendedConsumerProperties;
import org.springframework.cloud.stream.binder.kafka.KafkaMessageChannelBinder;
import org.springframework.cloud.stream.binder.kafka.ListenerContainerWithDlqAndRetryCustomizer;
import org.springframework.cloud.stream.binder.kafka.properties.KafkaConsumerProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.AbstractMessageListenerContainer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer.HeaderNames;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.util.StringUtils;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * Moves retrying failed records from the binding to the listener container.
 *
 * <p>The binding retries on the consumer thread without polling in between, so a downstream outage
 * longer than max.poll.interval.ms gets the consumer evicted from its group. The container's error
 * handler instead seeks back to the failed record and re-polls it, so records that failed for a
 * transient reason can be retried until the outage is over. Any other failure sends the record to
 * the dead letter topic right away.
 *
 * <p>Only takes effect while the binding's maxAttempts is greater than 1 (the default is 3),
 * otherwise the binder handles failures via its error channel instead.
 */
@Configuration
@ConditionalOnExpression("${services.kafka.enabled} and ${services.kafka.processor.enabled}")
public class KafkaErrorHandlerConfig {

  // kept short, as the in-thread retries before giving up on a record already back off
  private static final long INITIAL_BACK_OFF_MILLIS = 5_000;
  private static final long MAX_BACK_OFF_MILLIS = 60_000;

  @Bean
  ListenerContainerWithDlqAndRetryCustomizer retryTransientFailuresCustomizer(
      KafkaOperations<?, ?> kafkaOperations) {
    return new ListenerContainerWithDlqAndRetryCustomizer() {
      @Override
      public void configure(
          AbstractMessageListenerContainer<?, ?> container,
          String destinationName,
          String group,
          @Nullable BiFunction<ConsumerRecord<?, ?>, Exception, TopicPartition>
              dlqDestinationResolver,
          @Nullable BackOff backOff,
          ExtendedConsumerProperties<KafkaConsumerProperties> extendedConsumerProperties) {
        container.setCommonErrorHandler(
            createErrorHandler(kafkaOperations, group, extendedConsumerProperties.getExtension()));
      }

      @Override
      public void configure(
          AbstractMessageListenerContainer<?, ?> container,
          String destinationName,
          String group,
          @Nullable BiFunction<ConsumerRecord<?, ?>, Exception, TopicPartition>
              dlqDestinationResolver,
          @Nullable BackOff backOff) {
        // never invoked by the binder, which calls the overload with the consumer properties
      }

      @Override
      public boolean retryAndDlqInBinding(String destinationName, String group) {
        return false;
      }
    };
  }

  static DefaultErrorHandler createErrorHandler(
      KafkaOperations<?, ?> kafkaOperations, String group, KafkaConsumerProperties properties) {
    DeadLetterPublishingRecoverer recoverer = null;
    if (properties.isEnableDlq()) {
      recoverer =
          new DeadLetterPublishingRecoverer(
              kafkaOperations, (record, exc) -> deadLetterDestination(record, group, properties));
      recoverer.setHeaderNamesSupplier(KafkaErrorHandlerConfig::binderDeadLetterHeaderNames);
    }

    var backOff = new ExponentialBackOff(INITIAL_BACK_OFF_MILLIS, 2);
    backOff.setMaxInterval(MAX_BACK_OFF_MILLIS);

    var errorHandler = new DefaultErrorHandler(recoverer, backOff);
    errorHandler.defaultFalse();
    errorHandler.addRetryableExceptions(TransientProcessingException.class);
    return errorHandler;
  }

  /** The same dead letter topic the binder itself would have sent the record to. */
  static TopicPartition deadLetterDestination(
      ConsumerRecord<?, ?> record, String group, KafkaConsumerProperties properties) {
    var topic =
        StringUtils.hasText(properties.getDlqName())
            ? properties.getDlqName()
            : "error." + record.topic() + "." + group;
    var dlqPartitions = properties.getDlqPartitions();
    var partition = dlqPartitions == null || dlqPartitions > 1 ? record.partition() : 0;
    return new TopicPartition(topic, partition);
  }

  /** The headers the binder itself would have added to the dead letter record. */
  private static HeaderNames binderDeadLetterHeaderNames() {
    return HeaderNames.Builder.original()
        .offsetHeader(KafkaMessageChannelBinder.X_ORIGINAL_OFFSET)
        .timestampHeader(KafkaMessageChannelBinder.X_ORIGINAL_TIMESTAMP)
        .timestampTypeHeader(KafkaMessageChannelBinder.X_ORIGINAL_TIMESTAMP_TYPE)
        .topicHeader(KafkaMessageChannelBinder.X_ORIGINAL_TOPIC)
        .partitionHeader(KafkaMessageChannelBinder.X_ORIGINAL_PARTITION)
        .consumerGroupHeader(KafkaHeaders.DLT_ORIGINAL_CONSUMER_GROUP)
        .exception()
        .keyExceptionFqcn(KafkaHeaders.DLT_KEY_EXCEPTION_FQCN)
        .exceptionFqcn(KafkaMessageChannelBinder.X_EXCEPTION_FQCN)
        .exceptionCauseFqcn(KafkaHeaders.DLT_EXCEPTION_CAUSE_FQCN)
        .keyExceptionMessage(KafkaHeaders.DLT_KEY_EXCEPTION_MESSAGE)
        .exceptionMessage(KafkaMessageChannelBinder.X_EXCEPTION_MESSAGE)
        .keyExceptionStacktrace(KafkaHeaders.DLT_KEY_EXCEPTION_STACKTRACE)
        .exceptionStacktrace(KafkaMessageChannelBinder.X_EXCEPTION_STACKTRACE)
        .build();
  }
}
