package org.miracum.etl.fhirgateway.processors;

import org.apache.commons.codec.digest.HmacAlgorithms;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties(prefix = "services.kafka.processor")
@Validated
public record KafkaProcessorConfig(
    GenerateOutputTopic generateOutputTopic, CryptoHashMessageKeys cryptoHashMessageKeys) {
  public record GenerateOutputTopic(String matchExpression, String replaceWith) {}

  public record CryptoHashMessageKeys(boolean enabled, HmacAlgorithms algorithm, String key) {}
}
