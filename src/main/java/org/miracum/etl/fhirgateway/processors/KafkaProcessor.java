package org.miracum.etl.fhirgateway.processors;

import static net.logstash.logback.argument.StructuredArguments.kv;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.apache.commons.codec.digest.HmacUtils;
import org.apache.commons.lang3.StringUtils;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Resource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.integration.support.MessageBuilder;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.Message;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnExpression(
    "${services.kafka.enabled} and ${services.kafka.processor.enabled} and !${services.kafka.processor.consume-only}")
public class KafkaProcessor extends BaseKafkaProcessor {

  private final String generateTopicMatchExpression;
  private final String generateTopicReplacement;
  private final Pattern topicPattern;
  private final KafkaProcessorConfig.CryptoHashMessageKeys cryptoHash;

  public KafkaProcessor(ResourcePipeline pipeline, KafkaProcessorConfig config) {
    super(pipeline);
    this.generateTopicMatchExpression = config.generateOutputTopic().matchExpression();
    this.generateTopicReplacement = config.generateOutputTopic().replaceWith();
    this.topicPattern = Pattern.compile(generateTopicMatchExpression);
    this.cryptoHash = config.cryptoHashMessageKeys();
  }

  @Bean
  Function<Message<List<Resource>>, List<Message<Bundle>>> process() {
    return messages -> {
      var resources = messages.getPayload();
      if (resources.isEmpty()) {
        LOG.warn("messages is empty. Ignoring.");
        return List.of();
      }

      var processedBundles = super.processBatch(messages);

      var result = new ArrayList<Message<Bundle>>(resources.size());
      for (var i = 0; i < resources.size(); i++) {
        var processed = processedBundles.get(i);

        var originalMessageKey =
            Objects.toString(getBatchHeader(messages, KafkaHeaders.RECEIVED_KEY, i), "");
        var outputMessageKey = originalMessageKey;

        if (cryptoHash.enabled()) {
          // potentially consider clone()
          // <https://waseemh.github.io/thread-safe-mac-calculation>
          outputMessageKey =
              new HmacUtils(cryptoHash.algorithm(), cryptoHash.key()).hmacHex(originalMessageKey);
        }

        var messageBuilder =
            MessageBuilder.withPayload(processed).setHeader(KafkaHeaders.KEY, outputMessageKey);

        var inputTopic =
            Objects.requireNonNull(
                (String) getBatchHeader(messages, KafkaHeaders.RECEIVED_TOPIC, i));

        var outputTopic = computeOutputTopicFromInputTopic(inputTopic);
        // see https://github.com/spring-cloud/spring-cloud-stream/issues/1909 and
        // https://docs.spring.io/spring-cloud-stream/reference/spring-cloud-stream/event-routing.html#routing-from-consumer
        outputTopic.ifPresent(
            s -> messageBuilder.setHeader("spring.cloud.stream.sendto.destination", s));

        result.add(messageBuilder.build());
      }

      return result;
    };
  }

  private Optional<String> computeOutputTopicFromInputTopic(String inputTopic) {
    if (StringUtils.isNotBlank(generateTopicMatchExpression)
        && StringUtils.isNotBlank(generateTopicReplacement)) {
      var outputTopic = topicPattern.matcher(inputTopic).replaceFirst(generateTopicReplacement);

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
