package org.miracum.etl.fhirgateway.processors;

import org.emau.icmvc.ganimed.ttp.psn.PSNManager;
import org.emau.icmvc.ganimed.ttp.psn.exceptions.DBException;
import org.emau.icmvc.ganimed.ttp.psn.exceptions.InvalidGeneratorException;
import org.emau.icmvc.ganimed.ttp.psn.exceptions.InvalidParameterException;
import org.emau.icmvc.ganimed.ttp.psn.exceptions.UnknownDomainException;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.*;
import org.miracum.etl.fhirgateway.FhirSystemsConfig;
import org.springframework.beans.factory.annotation.Value;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@org.springframework.stereotype.Service
public class GpasPseudonymizer {
    private final PSNManager gpasManager;
    private final FhirSystemsConfig fhirSystems;
    @Value("${services.gpas.domains.patient}")
    private String gpasPatientDomain;
    @Value("${services.gpas.domains.case}")
    private String gpasCaseDomain;
    @Value("${services.gpas.domains.report}")
    private String gpasReportDomain;

    public GpasPseudonymizer(PSNManager gpasManager, FhirSystemsConfig fhirSystems) {
        this.gpasManager = gpasManager;
        this.fhirSystems = fhirSystems;
    }

    private static String getIdFromReference(Reference reference) {
        return reference.getReference().split("/")[1];
    }

    public List<IBaseResource> process(List<IBaseResource> resources) throws InvalidParameterException, DBException, InvalidGeneratorException, UnknownDomainException {
        var patIds = new HashSet<String>();
        var caseIds = new HashSet<String>();
        var reportIds = new HashSet<String>();

        // collect IDs for each of the different FHIR Ressource Types
        for (var resource : resources) {
            //TODO waiting for https://openjdk.java.net/jeps/8213076 to clean this up...

            if (resource instanceof Patient) {
                var patient = (Patient) resource;
                patIds.add(patient.getId());
            } else if (resource instanceof Encounter) {
                var encounter = (Encounter) resource;
                caseIds.add(encounter.getId());
                patIds.add(getIdFromReference(encounter.getSubject()));
            } else if (resource instanceof Observation) {
                var observation = (Observation) resource;
                patIds.add(getIdFromReference(observation.getSubject()));
                caseIds.add(getIdFromReference(observation.getEncounter()));
            } else if (resource instanceof Procedure) {
                var procedure = (Procedure) resource;
                patIds.add(getIdFromReference(procedure.getSubject()));
                caseIds.add(getIdFromReference(procedure.getEncounter()));
            } else if (resource instanceof Condition) {
                var condition = (Condition) resource;
                patIds.add(getIdFromReference(condition.getSubject()));
                caseIds.add(getIdFromReference(condition.getEncounter()));
            } else if (resource instanceof DiagnosticReport) {
                var report = (DiagnosticReport) resource;
                reportIds.add(report.getId());
                patIds.add(getIdFromReference(report.getSubject()));
                caseIds.add(getIdFromReference(report.getEncounter()));
            }
        }

        // call gPAS for each of the pseudonym domains
        Map<String, String> pseudoPatIds = new HashMap<>();
        Map<String, String> pseudoCaseIds = new HashMap<>();
        Map<String, String> pseudoReportIds = new HashMap<>();

        if (!patIds.isEmpty()) {
            pseudoPatIds = gpasManager.getOrCreatePseudonymForList(patIds, gpasPatientDomain);
        }

        if (!caseIds.isEmpty()) {
            pseudoCaseIds = gpasManager.getOrCreatePseudonymForList(caseIds, gpasCaseDomain);
        }

        if (!reportIds.isEmpty()) {
            pseudoReportIds = gpasManager.getOrCreatePseudonymForList(reportIds, gpasReportDomain);
        }

        // substitute identifier and references with pseudonyms
        for (var resource : resources) {
            if (resource instanceof Patient) {
                var patient = (Patient) resource;
                var pseudoPatId = pseudoPatIds.get(patient.getId());
                patient.setId(pseudoPatId);

                for (var identifier : patient.getIdentifier()) {
                    if (identifier.getSystem().equals(fhirSystems.getPatientId())) {
                        identifier.setValue(pseudoPatId);
                    }
                }

                // remove IDAT: Insurance-ID
                var withoutInsuranceId = patient.getIdentifier()
                        .stream()
                        .filter(id -> !id.getSystem().equals(fhirSystems.getInsuranceNumber()))
                        .collect(Collectors.toList());
                patient.setIdentifier(withoutInsuranceId);
            } else if (resource instanceof Encounter) {
                var encounter = (Encounter) resource;
                var pseudoCid = pseudoCaseIds.get(encounter.getId());
                var pseudoPid = pseudoPatIds.get(getIdFromReference(encounter.getSubject()));
                encounter.setId(pseudoCid);

                for (var identifier : encounter.getIdentifier()) {
                    if (identifier.getSystem().equals(fhirSystems.getEncounterId())) {
                        identifier.setValue(pseudoCid);
                    }
                }

                encounter.getSubject().setReference("Patient/" + pseudoPid);
            } else if (resource instanceof Observation) {
                var observation = (Observation) resource;
                var pseudoCid = pseudoCaseIds.get(getIdFromReference(observation.getEncounter()));
                var pseudoPid = pseudoPatIds.get(getIdFromReference(observation.getSubject()));

                observation.getEncounter().setReference("Encounter/" + pseudoCid);
                observation.getSubject().setReference("Patient/" + pseudoPid);
            } else if (resource instanceof Procedure) {
                var procedure = (Procedure) resource;
                var pseudoCid = pseudoCaseIds.get(getIdFromReference(procedure.getEncounter()));
                var pseudoPid = pseudoPatIds.get(getIdFromReference(procedure.getSubject()));

                procedure.getEncounter().setReference("Encounter/" + pseudoCid);
                procedure.getSubject().setReference("Patient/" + pseudoPid);
            } else if (resource instanceof Condition) {
                var condition = (Condition) resource;
                var pseudoCid = pseudoCaseIds.get(getIdFromReference(condition.getEncounter()));
                var pseudoPid = pseudoPatIds.get(getIdFromReference(condition.getSubject()));

                condition.getEncounter().setReference("Encounter/" + pseudoCid);
                condition.getSubject().setReference("Patient/" + pseudoPid);
            } else if (resource instanceof DiagnosticReport) {
                var report = (DiagnosticReport) resource;
                var pseudoRepid = pseudoReportIds.get(report.getId());
                var pseudoCid = pseudoCaseIds.get(getIdFromReference(report.getEncounter()));
                var pseudoPid = pseudoPatIds.get(getIdFromReference(report.getSubject()));
                report.setId(pseudoRepid);

                for (var identifier : report.getIdentifier()) {
                    if (identifier.getSystem().equals(fhirSystems.getReportId())) {
                        identifier.setValue(pseudoRepid);
                    }
                }

                report.getEncounter().setReference("Encounter/" + pseudoCid);
                report.getSubject().setReference("Patient/" + pseudoPid);
            }
        }

        return resources;
    }
}
