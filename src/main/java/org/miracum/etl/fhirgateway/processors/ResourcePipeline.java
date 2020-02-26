package org.miracum.etl.fhirgateway.processors;

import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Observation;
import org.miracum.etl.fhirgateway.stores.FhirResourceRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
public class ResourcePipeline {
    private final FhirResourceRepository store;
    private LoincHarmonizer loincHarmonizer;
    private AbstractPseudonymizer pseudonymizer;
    private boolean isLoincHarmonizationEnabled;

    public ResourcePipeline(
            FhirResourceRepository store,
            LoincHarmonizer loincHarmonizer,
            AbstractPseudonymizer pseudonymizer,
            @Value("${services.loinc.conversions.enabled}") boolean isLoincHarmonizationEnabled) {
        this.store = store;
        this.loincHarmonizer = loincHarmonizer;
        this.pseudonymizer = pseudonymizer;
        this.isLoincHarmonizationEnabled = isLoincHarmonizationEnabled;
    }

    public void process(List<IBaseResource> resources) throws Exception {
        // pseudonymization should be the first task to ensure all other processors only
        // ever work with de-identified data.
        var pseudonymized = pseudonymizer.process(resources);

        // this logic may be refactored and cleaned up by creating a genuine pipeline class with optionally
        // added stages. A base for this would be an abstract ResourceProcessor
        if (this.isLoincHarmonizationEnabled) {
            var harmonized = pseudonymized.stream()
                    .map(resource -> resource instanceof Observation ?
                            loincHarmonizer.process((Observation) resource) : resource)
                    .collect(Collectors.toList());

            store.save(harmonized);
        } else {
            store.save(pseudonymized);
        }
    }
}
