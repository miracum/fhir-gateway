package org.miracum.etl.fhirgateway.stores;

import static net.logstash.logback.argument.StructuredArguments.kv;

import java.util.Objects;
import java.util.concurrent.ExecutionException;
import org.hl7.fhir.r4.model.Bundle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnExpression("${services.kafka.enabled} and ${services.kafka.store-from-api.enabled}")
public class KafkaFhirResourceRepository implements FhirResourceRepository {

  private static final Logger log = LoggerFactory.getLogger(KafkaFhirResourceRepository.class);

  private final KafkaTemplate<String, Bundle> kafkaTemplate;
  private final String topic;

  public KafkaFhirResourceRepository(
      @Value("${services.kafka.store-from-api.output-topic}") String topic,
      KafkaTemplate<String, Bundle> kafkaTemplate) {
    this.topic = topic;
    this.kafkaTemplate = kafkaTemplate;
    kafkaTemplate.setDefaultTopic(topic);
  }

  @Override
  public void save(Bundle bundle) {
    // entries without a resource, e.g. DELETEs, have no fullUrl, so fall back to the request url
    var key =
        bundle.getEntry().stream()
            .map(entry -> entry.hasFullUrl() ? entry.getFullUrl() : entry.getRequest().getUrl())
            .filter(Objects::nonNull)
            .findFirst();
    if (key.isPresent()) {
      log.debug("writing bundle {} to {}", kv("key", key.get()), kv("topic", topic));
      // sending is asynchronous, so wait for the broker to acknowledge the write to make failures
      // surface to the caller. Transient failures are already retried by the producer itself, up
      // to its delivery.timeout.ms.
      try {
        kafkaTemplate.sendDefault(key.get(), bundle).get();
      } catch (InterruptedException exc) {
        Thread.currentThread().interrupt();
        throw new KafkaException("Interrupted while writing bundle to " + topic, exc);
      } catch (ExecutionException exc) {
        throw new KafkaException("Failed to write bundle to " + topic, exc.getCause());
      }
    } else {
      log.warn("no resource found in bundle");
    }
  }
}
