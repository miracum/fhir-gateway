package org.miracum.etl.fhirgateway.processors;

import java.util.function.Consumer;
import org.hl7.fhir.r4.model.Resource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.messaging.Message;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnExpression(
    "${services.kafka.enabled} and ${services.kafka.processor.enabled} and ${services.kafka.processor.consume-only}")
public class KafkaConsumer extends BaseKafkaProcessor {

  @Autowired
  public KafkaConsumer(ResourcePipeline pipeline) {
    super(pipeline);
  }

  @Bean
  public Consumer<Message<Resource>> process() {
    return super::process;
  }
}
