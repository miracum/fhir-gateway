package org.miracum.etl.fhirgateway.processors;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

public class KafkaProcessorTests {

  @Nested
  @SpringBootTest
  @ActiveProfiles("test")
  @TestPropertySource(
      properties = {"services.kafka.enabled=true", "services.kafka.consumeOnly=false"})
  public class KafkaConsumerProducerTests {

    @Autowired private BaseKafkaProcessor processor;

    @Test
    public void processorShouldBeEnabled() {
      assertThat(processor).isInstanceOf(KafkaProcessor.class);
    }
  }

  @Nested
  @SpringBootTest
  @ActiveProfiles("test")
  @TestPropertySource(
      properties = {"services.kafka.enabled=true", "services.kafka.consumeOnly=true"})
  public class KafkaConsumerTests {

    @Autowired private BaseKafkaProcessor consumer;

    @Test
    public void consumerShouldBeEnabled() {
      assertThat(consumer).isInstanceOf(KafkaConsumer.class);
    }
  }

  @Nested
  @SpringBootTest
  @ActiveProfiles("test")
  @TestPropertySource(properties = {"services.kafka.enabled=false"})
  public class KafkaDisabledTests {

    @Autowired(required = false)
    private BaseKafkaProcessor processor;

    @Test
    public void processorIsDisabled() {
      assertThat(processor).isNull();
    }
  }
}
