package org.miracum.etl.fhirgateway.processors;

import org.hl7.fhir.instance.model.api.IBaseResource;

import java.util.List;

public class NullPseudonymizer extends AbstractPseudonymizer {

    @Override
    public List<IBaseResource> process(List<IBaseResource> resources) {
        return resources;
    }
}
