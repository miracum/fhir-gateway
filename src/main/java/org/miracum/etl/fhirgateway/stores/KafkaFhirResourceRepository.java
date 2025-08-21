package org.miracum.etl.fhirgateway.stores;

import static net.logstash.logback.argument.StructuredArguments.kv;

import org.hl7.fhir.r4.model.Bundle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnExpression("${services.kafka.enabled} and ${services.kafka.produce-from-api.enabled}")
public class KafkaFhirResourceRepository implements FhirResourceRepository {

  private static final Logger log = LoggerFactory.getLogger(KafkaFhirResourceRepository.class);

  private final RetryTemplate retryTemplate;
  private final KafkaTemplate<String, Bundle> kafkaTemplate;
  private final String topic;

  public KafkaFhirResourceRepository(
      @Qualifier("kafkaRetryTemplate") RetryTemplate retryTemplate,
      @Value("${services.kafka.produceFromApi.output-topic}") String topic,
      KafkaTemplate<String, Bundle> kafkaTemplate) {
    this.retryTemplate = retryTemplate;
    this.topic = topic;
    this.kafkaTemplate = kafkaTemplate;
    kafkaTemplate.setDefaultTopic(topic);
  }

  @Override
  public void save(Bundle bundle) {
    var key = bundle.getEntry().stream().map(Bundle.BundleEntryComponent::getFullUrl).findFirst();
    if (key.isPresent()) {
      log.debug("writing bundle {} to {}", kv(key.get(), "key"), kv(topic, "topic"));
      retryTemplate.execute(retryContext -> kafkaTemplate.sendDefault(key.get(), bundle));
    } else {
      log.warn("no resource found in bundle");
    }
  }
}
