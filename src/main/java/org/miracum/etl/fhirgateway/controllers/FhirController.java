package org.miracum.etl.fhirgateway.controllers;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.IParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.UUID;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Bundle.BundleType;
import org.hl7.fhir.r4.model.Resource;
import org.miracum.etl.fhirgateway.processors.ResourcePipeline;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(
    value = "/fhir",
    produces = {"application/json", "application/fhir+json"})
@ResponseBody
public class FhirController {

  private static final Logger log = LoggerFactory.getLogger(FhirController.class);

  private final IParser fhirParser;
  private final ResourcePipeline pipeline;

  @Autowired
  public FhirController(FhirContext fhirContext, ResourcePipeline pipeline) {
    this.fhirParser = fhirContext.newJsonParser();
    this.pipeline = pipeline;
  }

  @RequestMapping(method = {RequestMethod.POST})
  public ResponseEntity<String> postFhirRoot(@RequestBody String body) throws Exception {
    if (body == null) {
      return new ResponseEntity<>(HttpStatus.BAD_REQUEST);
    }

    var resource = fhirParser.parseResource(body);

    if (resource instanceof Bundle) {
      var bundle = (Bundle) resource;
      log.debug("Got bundle of size {}", bundle.getEntry().size());

      if (bundle.isEmpty()) {
        log.debug("Received empty bundle");
        return new ResponseEntity<>(HttpStatus.BAD_REQUEST);
      }

      var processed = pipeline.process(bundle);
      return ResponseEntity.ok(fhirParser.encodeResourceToString(processed));
    } else {
      log.debug("Received a non-Bundle resource on the base endpoint");
      return ResponseEntity.badRequest().build();
    }
  }

  @RequestMapping(value = "/metadata", method = RequestMethod.GET)
  public Object getCapabilities() throws IOException {
    var resource = new ClassPathResource("/static/fhir-metadata.json");
    var mapper = new ObjectMapper();

    return mapper.readValue(resource.getInputStream(), Object.class);
  }

  @RequestMapping(
      value = {"/{resourceName}", "/{resourceName}/{id}"},
      method = {RequestMethod.PUT, RequestMethod.POST})
  public ResponseEntity<String> postPutFhirResource(
      @PathVariable(value = "resourceName") String resourceName,
      @PathVariable(value = "id", required = false) String id,
      @RequestBody String body) {
    if (body == null) {
      log.debug("Request body is empty");
      return new ResponseEntity<>(HttpStatus.BAD_REQUEST);
    }

    var resource = fhirParser.parseResource(body);

    var bundle = new Bundle();
    if (resource instanceof Bundle) {
      bundle = (Bundle) resource;
    } else {
      bundle.setType(BundleType.TRANSACTION);
      bundle.setId(UUID.randomUUID().toString());
      bundle.addEntry().setResource((Resource) resource);
    }

    var processed = pipeline.process(bundle);
    return ResponseEntity.ok(fhirParser.encodeResourceToString(processed));
  }
}
