package org.miracum.etl.fhirgateway.processors;

import org.emau.icmvc.ganimed.ttp.psn.exceptions.DBException;
import org.emau.icmvc.ganimed.ttp.psn.exceptions.InvalidGeneratorException;
import org.emau.icmvc.ganimed.ttp.psn.exceptions.InvalidParameterException;
import org.emau.icmvc.ganimed.ttp.psn.exceptions.UnknownDomainException;
import org.hl7.fhir.r4.model.Bundle;

public abstract class AbstractPseudonymizer {
    public abstract Bundle process(Bundle bundle) throws InvalidParameterException, DBException, InvalidGeneratorException, UnknownDomainException;
}
