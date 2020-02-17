package org.miracum.etl.fhirgateway.processors;

import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Observation;
import org.miracum.etl.fhirgateway.stores.FhirResourceRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
public class ResourcePipeline {
    private final FhirResourceRepository store;
    private LoincHarmonizer loincHarmonizer;
    private GpasPseudonymizer gpasPseudonymizer;

    public ResourcePipeline(
            FhirResourceRepository store,
            LoincHarmonizer loincHarmonizer,
            GpasPseudonymizer gpasPseudonymizer) {
        this.store = store;
        this.loincHarmonizer = loincHarmonizer;
        this.gpasPseudonymizer = gpasPseudonymizer;
    }

    public void process(List<IBaseResource> resources) throws Exception {
        // pseudonymization should be the first task to ensure all other processors only
        // ever work with de-identified data.
        var pseudonymized = gpasPseudonymizer.process(resources);

        var harmonized = pseudonymized.stream()
                .map(resource -> resource instanceof Observation ?
                        loincHarmonizer.process((Observation) resource) : resource)
                .collect(Collectors.toList());

        store.save(harmonized);
    }
}
