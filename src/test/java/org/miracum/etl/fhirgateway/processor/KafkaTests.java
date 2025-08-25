package org.miracum.etl.fhirgateway.processor;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.miracum.etl.fhirgateway.processors.BaseKafkaProcessor;
import org.miracum.etl.fhirgateway.processors.KafkaConsumer;
import org.miracum.etl.fhirgateway.processors.KafkaProcessor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

public class KafkaTests {

  @Nested
  @SpringBootTest
  @ActiveProfiles("test")
  @TestPropertySource(
      properties = {
        "services.kafka.enabled=true",
        "services.kafka.processor.enabled=true",
        "services.kafka.processor.consume-only=false"
      })
  public class KafkaProcessorTests {

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
      properties = {
        "services.kafka.enabled=true",
        "services.kafka.processor.enabled=true",
        "services.kafka.processor.consume-only=true"
      })
  public class KafkaProcessorConsumeOnlyTests {

    @Autowired private BaseKafkaProcessor consumer;

    @Test
    public void consumerShouldBeEnabled() {
      assertThat(consumer).isInstanceOf(KafkaConsumer.class);
    }
  }

  @Nested
  @SpringBootTest
  @ActiveProfiles("test")
  @TestPropertySource(
      properties = {"services.kafka.enabled=true", "services.kafka.processor.enabled=false"})
  public class KafkaProcessorDisabledTests {

    @Autowired(required = false)
    private BaseKafkaProcessor processor;

    @Test
    public void processorIsDisabled() {
      assertThat(processor).isNull();
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
