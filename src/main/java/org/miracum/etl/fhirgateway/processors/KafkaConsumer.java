package org.miracum.etl.fhirgateway.processors;

import java.util.function.Consumer;
import org.hl7.fhir.r4.model.Resource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.messaging.Message;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(value = "services.kafka.enabled")
@ConditionalOnMissingBean(KafkaProcessor.class)
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
