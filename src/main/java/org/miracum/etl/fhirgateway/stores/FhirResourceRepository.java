package org.miracum.etl.fhirgateway.stores;

import org.hl7.fhir.instance.model.api.IBaseResource;

import java.util.List;

public interface FhirResourceRepository {

    void save(List<IBaseResource> resources);

    void save(IBaseResource resource);
}
