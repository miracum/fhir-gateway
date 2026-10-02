package org.miracum.etl.fhirgateway.processors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import org.apache.commons.codec.digest.HmacAlgorithms;
import org.apache.commons.codec.digest.HmacUtils;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Resource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.miracum.etl.fhirgateway.processors.KafkaProcessorConfig.CryptoHashMessageKeys;
import org.miracum.etl.fhirgateway.processors.KafkaProcessorConfig.GenerateOutputTopic;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.kafka.support.KafkaNull;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;

@ExtendWith(MockitoExtension.class)
class KafkaProcessorTest {
  private static final String SEND_TO_DESTINATION_HEADER = "spring.cloud.stream.sendto.destination";

  @Mock private ResourcePipeline pipeline;

  @Test
  void process_withOutputTopicExpression_sendsToTheGeneratedTopic() {
    when(pipeline.process(any(Bundle.class))).thenReturn(new Bundle());
    var processor =
        createProcessor(new GenerateOutputTopic("fhir\\.", "fhir.pseudonymized."), noKeyHashing());

    var result = processor.process().apply(createMessage("fhir.lab", "Patient/123"));

    assertThat(result.getHeaders().get(SEND_TO_DESTINATION_HEADER))
        .isEqualTo("fhir.pseudonymized.lab");
  }

  @Test
  void process_withoutOutputTopicExpression_usesTheBindingsDestination() {
    when(pipeline.process(any(Bundle.class))).thenReturn(new Bundle());
    var processor = createProcessor(new GenerateOutputTopic("", ""), noKeyHashing());

    var result = processor.process().apply(createMessage("fhir.lab", "Patient/123"));

    assertThat(result.getHeaders()).doesNotContainKey(SEND_TO_DESTINATION_HEADER);
    assertThat(result.getHeaders().get(KafkaHeaders.KEY)).isEqualTo("Patient/123");
  }

  @Test
  void process_withKeyHashingEnabled_replacesTheKeyWithItsHmac() {
    when(pipeline.process(any(Bundle.class))).thenReturn(new Bundle());
    var processor =
        createProcessor(
            new GenerateOutputTopic("", ""),
            new CryptoHashMessageKeys(true, HmacAlgorithms.HMAC_SHA_256, "secret"));

    var result = processor.process().apply(createMessage("fhir.lab", "Patient/123"));

    assertThat(result.getHeaders().get(KafkaHeaders.KEY))
        .isEqualTo(new HmacUtils(HmacAlgorithms.HMAC_SHA_256, "secret").hmacHex("Patient/123"));
  }

  @Test
  void process_withNullPayload_producesNoOutput() {
    var processor = createProcessor(new GenerateOutputTopic("", ""), noKeyHashing());
    @SuppressWarnings("unchecked")
    var message =
        (Message<Resource>)
            (Message<?>)
                MessageBuilder.withPayload(KafkaNull.INSTANCE)
                    .setHeader(KafkaHeaders.RECEIVED_TOPIC, "fhir.lab")
                    .build();

    assertThat(processor.process().apply(message)).isNull();
  }

  private KafkaProcessor createProcessor(
      GenerateOutputTopic generateOutputTopic, CryptoHashMessageKeys cryptoHashMessageKeys) {
    return new KafkaProcessor(
        pipeline, new KafkaProcessorConfig(generateOutputTopic, cryptoHashMessageKeys));
  }

  private static CryptoHashMessageKeys noKeyHashing() {
    return new CryptoHashMessageKeys(false, HmacAlgorithms.HMAC_SHA_256, "");
  }

  private static Message<Resource> createMessage(String topic, String key) {
    return MessageBuilder.<Resource>withPayload(new Patient().setId(key))
        .setHeader(KafkaHeaders.RECEIVED_TOPIC, topic)
        .setHeader(KafkaHeaders.RECEIVED_KEY, key)
        .build();
  }
}
