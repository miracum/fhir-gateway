package org.miracum.etl.fhirgateway.processors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ca.uhn.fhir.rest.server.exceptions.UnclassifiedServerFailureException;
import ca.uhn.fhir.rest.server.exceptions.UnprocessableEntityException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Patient;
import org.junit.jupiter.api.Test;
import org.miracum.kafka.serializers.KafkaFhirDeserializer;
import org.miracum.kafka.serializers.KafkaFhirSerializer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.stream.binder.kafka.KafkaMessageChannelBinder;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(
    properties = {
      "services.kafka.enabled=true",
      "services.kafka.processor.enabled=true",
      "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
      "spring.cloud.stream.kafka.binder.brokers=${spring.embedded.kafka.brokers}",
      "spring.cloud.stream.bindings.process-in-0.destination="
          + KafkaErrorHandlerConfigTest.INPUT_TOPIC,
      "spring.cloud.stream.bindings.process-in-0.group=" + KafkaErrorHandlerConfigTest.GROUP,
      // retrying in the binding instead would give up after this many attempts
      "spring.cloud.stream.bindings.process-in-0.consumer.max-attempts=2",
      "spring.cloud.stream.bindings.process-out-0.destination="
          + KafkaErrorHandlerConfigTest.OUTPUT_TOPIC,
    })
@ActiveProfiles("test")
@EmbeddedKafka(
    partitions = 1,
    topics = {KafkaErrorHandlerConfigTest.INPUT_TOPIC, KafkaErrorHandlerConfigTest.OUTPUT_TOPIC})
class KafkaErrorHandlerConfigTest {
  static final String INPUT_TOPIC = "fhir.test";
  static final String OUTPUT_TOPIC = "fhir.test.output";
  static final String GROUP = "fhir-gateway-test";
  private static final String DLQ_TOPIC = "error." + INPUT_TOPIC + "." + GROUP;
  private static final Duration TIMEOUT = Duration.ofSeconds(60);

  @MockitoBean private ResourcePipeline pipeline;

  @Autowired private EmbeddedKafkaBroker broker;

  @Test
  void process_withTransientFailure_isRetriedUntilItSucceeds() throws Exception {
    var key = "Patient/" + UUID.randomUUID();
    when(pipeline.process(any(Bundle.class)))
        .thenThrow(new UnclassifiedServerFailureException(503, "unavailable"))
        .thenThrow(new UnclassifiedServerFailureException(503, "unavailable"))
        .thenReturn(new Bundle());

    send(key);

    verify(pipeline, timeout(TIMEOUT.toMillis()).times(3)).process(any(Bundle.class));
    assertThat(pollForRecordsWithKey(key, List.of(OUTPUT_TOPIC, DLQ_TOPIC)))
        .extracting(ConsumerRecord::topic)
        .containsExactly(OUTPUT_TOPIC);
  }

  @Test
  void process_withPermanentFailure_isSentToTheDeadLetterTopic() throws Exception {
    var key = "Patient/" + UUID.randomUUID();
    when(pipeline.process(any(Bundle.class)))
        .thenThrow(new UnprocessableEntityException("value rejected"));

    send(key);

    var records = pollForRecordsWithKey(key, List.of(OUTPUT_TOPIC, DLQ_TOPIC));
    assertThat(records).extracting(ConsumerRecord::topic).containsExactly(DLQ_TOPIC);
    var deadLetter = records.getFirst();
    assertThat(deadLetter.value()).isInstanceOf(Patient.class);
    var originalTopic = deadLetter.headers().lastHeader(KafkaMessageChannelBinder.X_ORIGINAL_TOPIC);
    assertThat(new String(originalTopic.value(), StandardCharsets.UTF_8)).isEqualTo(INPUT_TOPIC);
    verify(pipeline).process(any(Bundle.class));
  }

  /** Polls the topics until a record with the given key shows up, or the timeout elapses. */
  private List<ConsumerRecord<String, IBaseResource>> pollForRecordsWithKey(
      String key, List<String> topics) {
    var found = new ArrayList<ConsumerRecord<String, IBaseResource>>();
    try (var consumer = createConsumer()) {
      consumer.subscribe(topics);
      var deadline = System.nanoTime() + TIMEOUT.toNanos();
      while (found.isEmpty() && System.nanoTime() < deadline) {
        for (var record : consumer.poll(Duration.ofSeconds(1))) {
          if (key.equals(record.key())) {
            found.add(record);
          }
        }
      }
    }
    return found;
  }

  private void send(String key) throws Exception {
    var patient = new Patient();
    patient.setId(key);

    var props = KafkaTestUtils.producerProps(broker);
    props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, KafkaFhirSerializer.class);
    try (var producer = new KafkaProducer<String, IBaseResource>(props)) {
      producer.send(new ProducerRecord<>(INPUT_TOPIC, key, patient)).get();
    }
  }

  private Consumer<String, IBaseResource> createConsumer() {
    var props = KafkaTestUtils.consumerProps(broker, "test-" + UUID.randomUUID(), false);
    props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, KafkaFhirDeserializer.class);
    return new KafkaConsumer<>(props);
  }
}
