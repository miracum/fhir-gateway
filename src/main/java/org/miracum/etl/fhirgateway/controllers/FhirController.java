package org.miracum.etl.fhirgateway.controllers;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.IParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Bundle;
import org.miracum.etl.fhirgateway.stores.FhirResourceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.retry.support.RetryTemplate;
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
    private final RetryTemplate retryTemplate;
    private final FhirResourceRepository store;

    @Autowired
    public FhirController(
            RetryTemplate retryTemplate,
            FhirContext fhirContext,
            FhirResourceRepository store) {
        this.retryTemplate = retryTemplate;
        this.fhirParser = fhirContext.newJsonParser();
        this.store = store;
    }

    @RequestMapping(method = {RequestMethod.POST})
    public ResponseEntity<String> fhirRoot(@RequestBody String body) {
        if (body == null) {
            return new ResponseEntity<>(HttpStatus.BAD_REQUEST);
        }

        var bundle = fhirParser.parseResource(Bundle.class, body);

        log.debug("Got bundle of size {}", bundle.getEntry().size());

        if (bundle.getEntry().size() == 0) {
            return new ResponseEntity<>(HttpStatus.BAD_REQUEST);
        }

        var resources = bundle.getEntry()
                .stream()
                .map(e -> (IBaseResource) e.getResource())
                .collect(Collectors.toList());

        store.save(resources);

        return ResponseEntity.ok(fhirParser.encodeResourceToString(bundle));
    }

    @RequestMapping(value = "/metadata", method = RequestMethod.GET)
    public Object getCapabilities() throws IOException {
        var resource = new ClassPathResource("/static/fhir-metadata.json");
        var mapper = new ObjectMapper();
        return mapper.readValue(resource.getInputStream(), Object.class);
    }

    @RequestMapping(value = "/{resourceName}", method = {RequestMethod.PUT, RequestMethod.POST})
    public ResponseEntity<String> fhirResource(@PathVariable(value = "resourceName") String resourceName,
                                               @RequestBody String body
    ) {
        if (body == null) {
            return new ResponseEntity<>(HttpStatus.BAD_REQUEST);
        }

        var resource = fhirParser.parseResource(body);

        log.debug("Got resource {}", resource);

        store.save(List.of(resource));

        return ResponseEntity.ok(fhirParser.encodeResourceToString(resource));
    }
}
