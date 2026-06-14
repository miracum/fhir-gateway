package org.miracum.etl.fhirgateway.processors;

import static net.logstash.logback.argument.StructuredArguments.kv;

import java.util.List;
import java.util.UUID;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Bundle.BundleType;
import org.hl7.fhir.r4.model.Bundle.HTTPVerb;
import org.hl7.fhir.r4.model.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.Message;

public abstract class BaseKafkaProcessor {

  protected final Logger LOG = LoggerFactory.getLogger(this.getClass());

  private final ResourcePipeline pipeline;

  protected BaseKafkaProcessor(ResourcePipeline pipeline) {
    this.pipeline = pipeline;
  }

  public Bundle process(Message<Resource> message) {
    return pipeline.process(toBundle(message));
  }

  public List<Bundle> processBatch(List<Message<Resource>> messages) {
    var bundles = messages.stream().map(this::toBundle).toList();
    return pipeline.processBatch(bundles);
  }

  private Bundle toBundle(Message<Resource> message) {
    var incomingTopic = message.getHeaders().get(KafkaHeaders.RECEIVED_TOPIC);
    var key = message.getHeaders().getOrDefault(KafkaHeaders.RECEIVED_KEY, null);
    var resource = message.getPayload();

    LOG.debug(
        "Processing {} from {} with {}",
        kv("resourceId", resource.getId()),
        kv("topic", incomingTopic),
        kv("key", key));

    if (resource instanceof Bundle b) {
      return b;
    }

    var bundle = new Bundle();
    bundle.setType(BundleType.TRANSACTION);
    bundle.setId(UUID.randomUUID().toString());
    bundle
        .addEntry()
        .setResource(resource)
        .setFullUrl(resource.getId())
        .getRequest()
        .setMethod(HTTPVerb.PUT)
        .setUrl(resource.getId());

    return bundle;
  }
}
