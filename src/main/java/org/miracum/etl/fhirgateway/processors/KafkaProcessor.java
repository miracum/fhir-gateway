package org.miracum.etl.fhirgateway.processors;

import static net.logstash.logback.argument.StructuredArguments.kv;

import jakarta.annotation.Nullable;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import org.apache.commons.codec.digest.HmacUtils;
import org.apache.commons.lang3.StringUtils;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Resource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.integration.support.MessageBuilder;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.Message;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(value = "services.kafka.enabled", matchIfMissing = true)
public class KafkaProcessor extends BaseKafkaProcessor {

  private final String generateTopicMatchExpression;
  private final String generateTopicReplacement;
  private final KafkaProcessorConfig config;
  @Nullable private HmacUtils hmac;

  public KafkaProcessor(ResourcePipeline pipeline, KafkaProcessorConfig config) {
    super(pipeline);
    this.generateTopicMatchExpression = config.generateOutputTopic().matchExpression();
    this.generateTopicReplacement = config.generateOutputTopic().replaceWith();
    this.config = config;

    if (config.cryptoHashMessageKeys().enabled()) {
      hmac =
          new HmacUtils(
              config.cryptoHashMessageKeys().algorithm(), config.cryptoHashMessageKeys().key());
    }
  }

  @Bean
  Function<Message<Resource>, Message<Bundle>> process() {
    return message -> {
      var processed = super.process(message);

      var messageKey = message.getHeaders().getOrDefault(KafkaHeaders.RECEIVED_KEY, "").toString();

      if (config.cryptoHashMessageKeys().enabled() && hmac != null) {
        messageKey = hmac.hmacHex(messageKey);
      }

      var messageBuilder =
          MessageBuilder.withPayload(processed).setHeader(KafkaHeaders.KEY, messageKey);

      var inputTopic =
          Objects.requireNonNull(message.getHeaders().get(KafkaHeaders.RECEIVED_TOPIC)).toString();

      var outputTopic = computeOutputTopicFromInputTopic(inputTopic);
      // see https://github.com/spring-cloud/spring-cloud-stream/issues/1909 for
      // details on "spring.cloud.stream.sendto.destination"
      outputTopic.ifPresent(
          s -> messageBuilder.setHeader("spring.cloud.stream.sendto.destination", s));

      return messageBuilder.build();
    };
  }

  private Optional<String> computeOutputTopicFromInputTopic(String inputTopic) {
    if (StringUtils.isNotBlank(generateTopicMatchExpression)
        && StringUtils.isNotBlank(generateTopicReplacement)) {
      var outputTopic =
          inputTopic.replaceFirst(generateTopicMatchExpression, generateTopicReplacement);

      LOG.debug(
          "Computed output topic using {} and {} from {} as {}",
          kv("generateTopicMatchExpression", generateTopicMatchExpression),
          kv("generateTopicReplacement", generateTopicReplacement),
          kv("inputTopic", inputTopic),
          kv("outputTopic", outputTopic));

      if (inputTopic.equals(outputTopic)) {
        return Optional.empty();
      }

      return Optional.of(outputTopic);
    }

    return Optional.empty();
  }
}
