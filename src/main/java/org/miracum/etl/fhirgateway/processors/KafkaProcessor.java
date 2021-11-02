package org.miracum.etl.fhirgateway.processors;

import static net.logstash.logback.argument.StructuredArguments.kv;

import java.util.Optional;
import java.util.function.Function;
import org.apache.commons.lang3.StringUtils;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Resource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.integration.support.MessageBuilder;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.Message;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnExpression(
    "${services.kafka.enabled:true} and !T(org.springframework.util.StringUtils).isEmpty('${spring.cloud.stream.bindings.process-out-0.destination:}')")
public class KafkaProcessor extends BaseKafkaProcessor {

  private String generateTopicMatchExpression;
  private String generateTopicReplacement;

  @Autowired
  public KafkaProcessor(
      ResourcePipeline pipeline,
      @Value("${services.kafka.generate-output-topic.match-expression}")
          String generateTopicMatchExpression,
      @Value("${services.kafka.generate-output-topic.replace-with}")
          String generateTopicReplacement) {
    super(pipeline);
    this.generateTopicMatchExpression = generateTopicMatchExpression;
    this.generateTopicReplacement = generateTopicReplacement;
  }

  @Bean
  public Function<Message<Resource>, Message<Bundle>> process() {
    return message -> {
      var processed = super.process(message);

      var messageKey =
          message.getHeaders().getOrDefault(KafkaHeaders.RECEIVED_MESSAGE_KEY, "").toString();

      var messageBuilder =
          MessageBuilder.withPayload(processed)
              .setHeaderIfAbsent(KafkaHeaders.MESSAGE_KEY, messageKey);

      var inputTopic = message.getHeaders().get(KafkaHeaders.RECEIVED_TOPIC).toString();

      var outputTopic = computeOutputTopicFromInputTopic(inputTopic);
      // see https://github.com/spring-cloud/spring-cloud-stream/issues/1909 for details on
      // "spring.cloud.stream.sendto.destination"
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

      return Optional.of(outputTopic);
    }

    return Optional.empty();
  }
}
