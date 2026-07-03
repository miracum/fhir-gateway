package org.miracum.etl.fhirgateway.processors;

import static net.logstash.logback.argument.StructuredArguments.kv;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Bundle.BundleType;
import org.hl7.fhir.r4.model.Bundle.HTTPVerb;
import org.hl7.fhir.r4.model.Resource;
import org.jspecify.annotations.Nullable;
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

  public List<Bundle> processBatch(Message<List<Resource>> messages) {
    var resources = messages.getPayload();

    var bundles = new ArrayList<Bundle>(resources.size());
    for (var i = 0; i < resources.size(); i++) {
      bundles.add(
          toBundle(
              resources.get(i),
              getBatchHeader(messages, KafkaHeaders.RECEIVED_TOPIC, i),
              getBatchHeader(messages, KafkaHeaders.RECEIVED_KEY, i)));
    }

    return pipeline.processBatch(bundles);
  }

  /**
   * With native decoding and batch mode enabled, per-record Kafka headers are exposed as lists on
   * the batch message, one entry per record, in the same order as the payload.
   */
  @SuppressWarnings("unchecked")
  @Nullable
  protected static Object getBatchHeader(
      Message<List<Resource>> message, String headerName, int index) {
    var header = (List<Object>) message.getHeaders().get(headerName);
    return header == null ? null : header.get(index);
  }

  private Bundle toBundle(Resource resource, @Nullable Object incomingTopic, @Nullable Object key) {
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
