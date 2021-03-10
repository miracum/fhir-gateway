package org.miracum.etl.fhirgateway.processors;

import java.util.function.Function;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Resource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.context.annotation.Bean;
import org.springframework.integration.support.MessageBuilder;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.Message;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnExpression(
    "!T(org.springframework.util.StringUtils).isEmpty('${spring.cloud.stream.bindings.process-out-0.destination:}')")
public class KafkaProcessor extends BaseKafkaProcessor {

  @Autowired
  public KafkaProcessor(ResourcePipeline pipeline, StreamBridge streamBridge) {
    super(pipeline);
  }

  @Bean
  public Function<Message<Resource>, Message<Bundle>> process() {
    return message -> {
      var processed = super.process(message);

      return MessageBuilder.withPayload(processed)
          .setHeaderIfAbsent(
              KafkaHeaders.MESSAGE_KEY,
              message.getHeaders().getOrDefault(KafkaHeaders.RECEIVED_MESSAGE_KEY, null))
          .build();
    };
  }
}
