package org.miracum.etl.fhirgateway.processors;

import java.util.UUID;
import java.util.function.Consumer;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Bundle.BundleType;
import org.hl7.fhir.r4.model.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(value = "services.kafka.enabled", matchIfMissing = true)
public class KafkaProcessor {

  private static final Logger LOG = LoggerFactory.getLogger(KafkaProcessor.class);

  private final ResourcePipeline pipeline;

  @Autowired
  public KafkaProcessor(ResourcePipeline pipeline) {
    this.pipeline = pipeline;
  }

  @Bean
  public Consumer<Resource> process() {
    return resource -> {
      if (resource == null) {
        LOG.warn("resource is null. Ignoring.");
        return;
      }

      LOG.debug("Processing resourceId={}", resource.getId());

      var bundle = new Bundle();
      if (resource instanceof Bundle) {
        bundle = (Bundle) resource;
      } else {
        bundle.setType(BundleType.TRANSACTION);
        bundle.setId(UUID.randomUUID().toString());
        bundle.addEntry().setResource(resource);
      }

      pipeline.process(bundle);
    };
  }
}