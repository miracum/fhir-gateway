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
import org.springframework.kafka.support.KafkaNull;
import org.springframework.messaging.Message;

public abstract class BaseKafkaProcessor {

  protected final Logger LOG = LoggerFactory.getLogger(this.getClass());

  private final ResourcePipeline pipeline;

  protected BaseKafkaProcessor(ResourcePipeline pipeline) {
    this.pipeline = pipeline;
  }

  /** A processed bundle paired with the topic/key of the Kafka record it came from. */
  protected record ProcessedRecord(Bundle bundle, @Nullable Object topic, @Nullable Object key) {}

  public List<Bundle> processBatch(Message<List<Resource>> messages) {
    return processBatchWithHeaders(messages).stream().map(ProcessedRecord::bundle).toList();
  }

  /**
   * Like {@link #processBatch(Message)}, but keeps each result paired with the topic/key of the
   * record it was built from - needed by callers (e.g. {@link KafkaProcessor}) that re-publish
   * per-record, since tombstones are dropped here and would otherwise desync a purely positional
   * lookup into the original batch.
   */
  protected List<ProcessedRecord> processBatchWithHeaders(Message<List<Resource>> messages) {
    // deliberately not List<Resource>: a tombstone (null-value) Kafka record surfaces here as a
    // KafkaNull instance, not a Resource, and List<Resource>.get(i) would throw a
    // ClassCastException before we ever get a chance to check for it.
    List<?> payloads = messages.getPayload();

    var pending = new ArrayList<ProcessedRecord>(payloads.size());
    for (var i = 0; i < payloads.size(); i++) {
      var payload = payloads.get(i);
      var topic = getBatchHeader(messages, KafkaHeaders.RECEIVED_TOPIC, i);
      var key = getBatchHeader(messages, KafkaHeaders.RECEIVED_KEY, i);

      if (payload instanceof KafkaNull) {
        LOG.debug(
            "Ignoring message with a null payload from {} with key {}",
            kv("topic", topic),
            kv("key", key));
        continue;
      }

      pending.add(new ProcessedRecord(toBundle((Resource) payload, topic, key), topic, key));
    }

    var bundles = pending.stream().map(ProcessedRecord::bundle).toList();
    var processedBundles = pipeline.processBatch(bundles);

    var result = new ArrayList<ProcessedRecord>(processedBundles.size());
    for (var i = 0; i < processedBundles.size(); i++) {
      result.add(
          new ProcessedRecord(
              processedBundles.get(i), pending.get(i).topic(), pending.get(i).key()));
    }
    return result;
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
