package org.miracum.etl.fhirgateway;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
@EmbeddedKafka(brokerProperties = {"listeners=PLAINTEXT://localhost:9094", "port=9094"})
class FhirGatewayApplicationTests {

  @Test
  void contextLoads() {}
}
