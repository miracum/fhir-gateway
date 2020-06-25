package org.miracum.etl.fhirgateway.processors;

import org.hl7.fhir.r4.model.Bundle;

public class NullPseudonymizer extends AbstractPseudonymizer {

    @Override
    public Bundle process(Bundle bundle) {
        return bundle;
    }
}
