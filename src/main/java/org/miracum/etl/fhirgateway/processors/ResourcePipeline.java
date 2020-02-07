package org.miracum.etl.fhirgateway.processors;

import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Observation;
import org.miracum.etl.fhirgateway.stores.FhirResourceRepository;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class ResourcePipeline {
    private final FhirResourceRepository store;
    private LoincHarmonizer loincHarmonizer;

    public ResourcePipeline(FhirResourceRepository store, LoincHarmonizer loincHarmonizer) {
        this.store = store;
        this.loincHarmonizer = loincHarmonizer;
    }

    public void process(List<IBaseResource> resources) throws Exception {
        var processed = new ArrayList<IBaseResource>();

        for (IBaseResource resource : resources) {
            IBaseResource iBaseResource = resource instanceof Observation ?
                    loincHarmonizer.process((Observation) resource) : resource;
            processed.add(iBaseResource);
        }

        store.save(processed);
    }
}
