package org.miracum.etl.fhirgateway.processors;

import org.emau.icmvc.ganimed.ttp.psn.exceptions.DBException;
import org.emau.icmvc.ganimed.ttp.psn.exceptions.InvalidGeneratorException;
import org.emau.icmvc.ganimed.ttp.psn.exceptions.InvalidParameterException;
import org.emau.icmvc.ganimed.ttp.psn.exceptions.UnknownDomainException;
import org.hl7.fhir.instance.model.api.IBaseResource;

import java.util.List;

public abstract class AbstractPseudonymizer {
    public abstract List<IBaseResource> process(List<IBaseResource> resources) throws InvalidParameterException, DBException, InvalidGeneratorException, UnknownDomainException;
}
