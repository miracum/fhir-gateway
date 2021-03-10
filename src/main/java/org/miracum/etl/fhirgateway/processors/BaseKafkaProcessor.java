package org.miracum.etl.fhirgateway.processors;

import static net.logstash.logback.argument.StructuredArguments.kv;

import java.util.UUID;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Bundle.BundleType;
import org.hl7.fhir.r4.model.Bundle.HTTPVerb;
import org.hl7.fhir.r4.model.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.Message;

@ConditionalOnProperty(value = "services.kafka.enabled", matchIfMissing = true)
public abstract class BaseKafkaProcessor {

  private final Logger LOG = LoggerFactory.getLogger(this.getClass());

  private final ResourcePipeline pipeline;

  public BaseKafkaProcessor(ResourcePipeline pipeline) {
    this.pipeline = pipeline;
  }

  public Bundle process(Message<Resource> message) {

    if (message == null) {
      LOG.warn("resource is null. Ignoring.");
      return null;
    }

    var incomingTopic = message.getHeaders().get(KafkaHeaders.RECEIVED_TOPIC);
    var key = message.getHeaders().getOrDefault(KafkaHeaders.RECEIVED_MESSAGE_KEY, null);
    var resource = message.getPayload();

    LOG.debug(
        "Processing {} from {} with {}",
        kv("resourceId", resource.getId()),
        kv("topic", incomingTopic),
        kv("key", key));

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

    return pipeline.process(bundle);
  }
}
