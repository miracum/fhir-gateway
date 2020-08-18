package org.miracum.etl.fhirgateway.processors;

import org.hl7.fhir.r4.model.Bundle;

public interface IPseudonymizer {

  Bundle process(Bundle bundle);
}
