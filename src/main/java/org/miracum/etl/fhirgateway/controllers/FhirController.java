package org.miracum.etl.fhirgateway.controllers;

import static net.logstash.logback.argument.StructuredArguments.kv;

import ca.uhn.fhir.context.FhirContext;
import com.google.common.base.Strings;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Bundle.BundleType;
import org.hl7.fhir.r4.model.Bundle.HTTPVerb;
import org.hl7.fhir.r4.model.Resource;
import org.miracum.etl.fhirgateway.processors.ResourcePipeline;
import org.miracum.etl.fhirgateway.stores.KafkaFhirResourceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(
    value = "/fhir",
    produces = {"application/json", "application/fhir+json"})
@ResponseBody
public class FhirController {
  private static final Logger log = LoggerFactory.getLogger(FhirController.class);

  private static final ClassPathResource CAPABILITY_STATEMENT =
      new ClassPathResource("static/fhir-metadata.json");

  private final FhirContext fhirContext;
  private final ResourcePipeline pipeline;
  private final Optional<KafkaFhirResourceRepository> kafkaStore;

  @Autowired
  public FhirController(
      FhirContext fhirContext,
      ResourcePipeline pipeline,
      Optional<KafkaFhirResourceRepository> kafkaStore) {
    this.fhirContext = fhirContext;
    this.pipeline = pipeline;
    this.kafkaStore = kafkaStore;
  }

  @PostMapping
  public ResponseEntity<String> postFhirRoot(@RequestBody String body) {
    if (body == null) {
      return new ResponseEntity<>(HttpStatus.BAD_REQUEST);
    }

    var parser = fhirContext.newJsonParser();
    var resource = parser.parseResource(body);

    if (resource instanceof Bundle bundle) {
      log.debug("Got bundle of size {}", kv("bundleSize", bundle.getEntry().size()));

      if (bundle.isEmpty()) {
        log.error("Received empty bundle");
        return new ResponseEntity<>(HttpStatus.BAD_REQUEST);
      }

      return ResponseEntity.ok(parser.encodeResourceToString(processAndStore(bundle)));
    } else {
      log.error("Received a non-Bundle resource on the base endpoint");
      return new ResponseEntity<>(HttpStatus.BAD_REQUEST);
    }
  }

  @GetMapping(value = "/metadata")
  public ResponseEntity<ClassPathResource> getCapabilities() {
    return ResponseEntity.ok(CAPABILITY_STATEMENT);
  }

  @PostMapping(value = {"/{resourceType}", "/{resourceType}/{id}"})
  public ResponseEntity<String> postResource(@RequestBody String body) {
    return handlePostPutResource(body, RequestMethod.POST);
  }

  @PutMapping(value = {"/{resourceType}", "/{resourceType}/{id}"})
  public ResponseEntity<String> putResource(@RequestBody String body) {
    return handlePostPutResource(body, RequestMethod.PUT);
  }

  @DeleteMapping(value = {"/{resourceType}/{id}"})
  public ResponseEntity<String> deleteResource(
      @PathVariable(value = "resourceType") String resourceType,
      @PathVariable(value = "id") String resourceId) {

    if (Strings.isNullOrEmpty(resourceId) || Strings.isNullOrEmpty(resourceType)) {
      log.error("resourceId or resourceType is empty.");
      return new ResponseEntity<>(HttpStatus.BAD_REQUEST);
    }

    var resourceUrl = String.format("%s/%s", resourceType, resourceId);

    var bundle = new Bundle();
    bundle.setType(BundleType.BATCH);
    bundle.setId(UUID.randomUUID().toString());
    bundle.addEntry().getRequest().setMethod(HTTPVerb.DELETE).setUrl(resourceUrl);

    var processed = processAndStore(bundle);
    return ResponseEntity.ok(fhirContext.newJsonParser().encodeResourceToString(processed));
  }

  private ResponseEntity<String> handlePostPutResource(String body, RequestMethod method) {
    if (body == null) {
      log.debug("Request body is empty");
      return new ResponseEntity<>(HttpStatus.BAD_REQUEST);
    }

    var httpMethodMap = Map.of(RequestMethod.POST, HTTPVerb.POST, RequestMethod.PUT, HTTPVerb.PUT);

    var parser = fhirContext.newJsonParser();
    var resource = (Resource) parser.parseResource(body);

    Bundle bundle;
    if (resource instanceof Bundle b) {
      bundle = b;
    } else {
      bundle = new Bundle();
      bundle.setType(BundleType.TRANSACTION);
      bundle.setId(UUID.randomUUID().toString());
      bundle
          .addEntry()
          .setResource(resource)
          .setFullUrl(resource.getId())
          .getRequest()
          .setMethod(httpMethodMap.getOrDefault(method, HTTPVerb.PUT))
          .setUrl(resource.getId());
    }

    return ResponseEntity.ok(parser.encodeResourceToString(processAndStore(bundle)));
  }

  /**
   * Runs the bundle through the pipeline and, if enabled, also stores the result in Kafka. Unlike
   * the other stores, which are part of the pipeline, this only applies to bundles received via
   * this API, not to the ones consumed from Kafka.
   */
  private Bundle processAndStore(Bundle bundle) {
    var processed = pipeline.process(bundle);
    kafkaStore.ifPresent(store -> store.save(processed));
    return processed;
  }
}
