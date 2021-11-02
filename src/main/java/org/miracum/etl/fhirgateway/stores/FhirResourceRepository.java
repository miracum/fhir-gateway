package org.miracum.etl.fhirgateway.stores;

import org.hl7.fhir.r4.model.Bundle;

public interface FhirResourceRepository {

  void save(Bundle bundle);
}
