package org.miracum.etl.fhirgateway.processors;

import java.util.UUID;
import java.util.function.Function;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Bundle.BundleType;
import org.hl7.fhir.r4.model.Bundle.HTTPVerb;
import org.hl7.fhir.r4.model.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.context.annotation.Bean;
import org.springframework.integration.support.MessageBuilder;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.Message;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(value = "services.kafka.enabled", matchIfMissing = true)
public class KafkaProcessor {

  private static final Logger LOG = LoggerFactory.getLogger(KafkaProcessor.class);

  private final ResourcePipeline pipeline;

  @Autowired
  public KafkaProcessor(ResourcePipeline pipeline, StreamBridge streamBridge) {
    this.pipeline = pipeline;
  }

  @Bean
  public Function<Message<Resource>, Message<Bundle>> process() {
    return message -> {
      if (message == null) {
        LOG.warn("resource is null. Ignoring.");
        return null;
      }

      var incomingTopic = message.getHeaders().get(KafkaHeaders.RECEIVED_TOPIC);
      var key = message.getHeaders().getOrDefault(KafkaHeaders.RECEIVED_MESSAGE_KEY, null);
      var resource = message.getPayload();

      LOG.debug(
          "Processing resourceId={} from topic={} with key={}",
          resource.getId(),
          incomingTopic,
          key);

      Bundle bundle;
      if (resource instanceof Bundle) {
        bundle = (Bundle) resource;
      } else {
        bundle = new Bundle();
        bundle.setType(BundleType.TRANSACTION);
        bundle.setId(UUID.randomUUID().toString());
        bundle
            .addEntry()
            .setResource(resource)
            .setFullUrl(resource.getId())
            .getRequest()
            .setMethod(HTTPVerb.PUT)
            .setUrl(resource.getId());
      }

      var processed = pipeline.process(bundle);

      return MessageBuilder.withPayload(processed)
          .setHeaderIfAbsent(KafkaHeaders.MESSAGE_KEY, key)
          .build();
    };
  }
}
