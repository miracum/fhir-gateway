package org.miracum.etl.fhirgateway.controllers;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.IParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Bundle.BundleType;
import org.hl7.fhir.r4.model.Bundle.HTTPVerb;
import org.hl7.fhir.r4.model.Resource;
import org.miracum.etl.fhirgateway.processors.ResourcePipeline;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
      method = RequestMethod.POST)
  public ResponseEntity<String> postResource(@RequestBody String body) {
    return handlePostPutResource(body, RequestMethod.POST);
  }

  @RequestMapping(
      value = {"/{resourceName}", "/{resourceName}/{id}"},
      method = RequestMethod.PUT)
  public ResponseEntity<String> putResource(@RequestBody String body) {
    return handlePostPutResource(body, RequestMethod.PUT);
  }

  private ResponseEntity<String> handlePostPutResource(String body, RequestMethod method) {
    if (body == null) {
      log.debug("Request body is empty");
      return new ResponseEntity<>(HttpStatus.BAD_REQUEST);
    }

    var httpMethodMap = Map.of(RequestMethod.POST, HTTPVerb.POST, RequestMethod.PUT, HTTPVerb.PUT);

    var resource = (Resource) fhirParser.parseResource(body);

    var bundle = new Bundle();
    if (resource instanceof Bundle) {
      bundle = (Bundle) resource;
    } else {
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

    var processed = pipeline.process(bundle);
    return ResponseEntity.ok(fhirParser.encodeResourceToString(processed));
  }
}
