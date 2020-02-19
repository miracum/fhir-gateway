package org.miracum.etl.fhirgateway.controllers;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.IParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Bundle;
import org.miracum.etl.fhirgateway.processors.ResourcePipeline;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping(value = "/fhir")
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
    public ResponseEntity<String> fhirRoot(@RequestBody String body) throws Exception {
        if (body == null) {
            return new ResponseEntity<>(HttpStatus.BAD_REQUEST);
        }

        var bundle = fhirParser.parseResource(Bundle.class, body);

        log.debug("Got bundle of size {}", bundle.getEntry().size());

        if (bundle.getEntry().size() == 0) {
            return new ResponseEntity<>(HttpStatus.BAD_REQUEST);
        }

        var resources =
                bundle.getEntry().stream()
                        .map(e -> (IBaseResource) e.getResource())
                        .collect(Collectors.toList());

        pipeline.process(resources);

        return ResponseEntity.ok(fhirParser.encodeResourceToString(bundle));
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
    public ResponseEntity<String> fhirResource(
            @PathVariable(value = "resourceName") String resourceName,
            @PathVariable(value = "id", required = false) String id,
            @RequestBody String body)
            throws Exception {
        if (body == null) {
            return new ResponseEntity<>(HttpStatus.BAD_REQUEST);
        }

        var resource = fhirParser.parseResource(body);

        log.debug("Got resource {}", resource);

        pipeline.process(List.of(resource));

        return ResponseEntity.ok(fhirParser.encodeResourceToString(resource));
    }
}
