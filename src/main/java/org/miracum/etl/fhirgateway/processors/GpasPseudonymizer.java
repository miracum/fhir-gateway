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

public class GpasPseudonymizer extends AbstractPseudonymizer {

    private final PSNManager psnManager;

    private final FhirSystemsConfig fhirSystems;

    @Value("${services.gpas.domains.patient}")
    private String gpasPatientDomain;

    @Value("${services.gpas.domains.case}")
    private String gpasCaseDomain;

    @Value("${services.gpas.domains.report}")
    private String gpasReportDomain;

    public GpasPseudonymizer(PSNManager psnManager, FhirSystemsConfig fhirSystems) {
        this.psnManager = psnManager;
        this.fhirSystems = fhirSystems;
    }

    private static String getIdFromReference(Reference reference) {
        return reference.getReferenceElement().getIdPart();
    }

    @Override
    public List<IBaseResource> process(List<IBaseResource> resources)
            throws InvalidParameterException, DBException, InvalidGeneratorException,
            UnknownDomainException {
        var patIds = new HashSet<String>();
        var caseIds = new HashSet<String>();
        var reportIds = new HashSet<String>();

        // collect IDs for each of the different FHIR Ressource Types
        for (var resource : resources) {
            // TODO waiting for https://openjdk.java.net/jeps/8213076 to clean this up...

            if (resource instanceof Patient) {
                var patient = (Patient) resource;
                patIds.add(patient.getIdElement().getIdPart());
            } else if (resource instanceof Encounter) {
                var encounter = (Encounter) resource;
                caseIds.add(encounter.getIdElement().getIdPart());

                if (encounter.hasSubject()) {
                    patIds.add(getIdFromReference(encounter.getSubject()));
                }
            } else if (resource instanceof Observation) {
                var observation = (Observation) resource;

                if (observation.hasSubject()) {
                    patIds.add(getIdFromReference(observation.getSubject()));
                }

                if (observation.hasEncounter()) {
                    caseIds.add(getIdFromReference(observation.getEncounter()));
                }
            } else if (resource instanceof Procedure) {
                var procedure = (Procedure) resource;

                if (procedure.hasSubject()) {
                    patIds.add(getIdFromReference(procedure.getSubject()));
                }

                if (procedure.hasEncounter()) {
                    caseIds.add(getIdFromReference(procedure.getEncounter()));
                }
            } else if (resource instanceof Condition) {
                var condition = (Condition) resource;

                if (condition.hasSubject()) {
                    patIds.add(getIdFromReference(condition.getSubject()));
                }

                if (condition.hasEncounter()) {
                    caseIds.add(getIdFromReference(condition.getEncounter()));
                }
            } else if (resource instanceof DiagnosticReport) {
                var report = (DiagnosticReport) resource;
                reportIds.add(report.getIdElement().getIdPart());

                if (report.hasSubject()) {
                    patIds.add(getIdFromReference(report.getSubject()));
                }

                if (report.hasEncounter()) {
                    caseIds.add(getIdFromReference(report.getEncounter()));
                }
            } else if (resource instanceof MedicationStatement) {
                var medicationStatement = (MedicationStatement) resource;

                if (medicationStatement.hasSubject() && medicationStatement.getSubject().getReferenceElement().getResourceType().equals("Patient")) {
                    patIds.add(getIdFromReference(medicationStatement.getSubject()));
                }

                if (medicationStatement.hasContext() && medicationStatement.getContext().getReferenceElement().getResourceType().equals("Encounter")) {
                    caseIds.add(getIdFromReference(medicationStatement.getContext()));
                }
            }
        }

        // call gPAS for each of the pseudonym domains
        Map<String, String> pseudoPatIds = new HashMap<>();
        Map<String, String> pseudoCaseIds = new HashMap<>();
        Map<String, String> pseudoReportIds = new HashMap<>();

        if (!patIds.isEmpty()) {
            pseudoPatIds = psnManager.getOrCreatePseudonymForList(patIds, gpasPatientDomain);
        }

        if (!caseIds.isEmpty()) {
            pseudoCaseIds = psnManager.getOrCreatePseudonymForList(caseIds, gpasCaseDomain);
        }

        if (!reportIds.isEmpty()) {
            pseudoReportIds = psnManager.getOrCreatePseudonymForList(reportIds, gpasReportDomain);
        }

        // substitute identifier and references with pseudonyms
        for (var resource : resources) {
            if (resource instanceof Patient) {
                var patient = (Patient) resource;
                var pseudoPatId = pseudoPatIds.get(patient.getIdElement().getIdPart());
                patient.setId(pseudoPatId);

                for (var identifier : patient.getIdentifier()) {
                    if (identifier.hasSystem() && identifier.getSystem().equals(fhirSystems.getPatientId())) {
                        identifier.setValue(pseudoPatId);
                    }
                }

                // remove IDAT: Insurance-ID
                var withoutInsuranceId =
                        patient.getIdentifier().stream()
                                .filter(Identifier::hasSystem)
                                .filter(id -> !id.getSystem().equals(fhirSystems.getInsuranceNumber()))
                                .collect(Collectors.toList());
                patient.setIdentifier(withoutInsuranceId);
            } else if (resource instanceof Encounter) {
                var encounter = (Encounter) resource;
                var pseudoCid = pseudoCaseIds.get(encounter.getIdElement().getIdPart());
                encounter.setId(pseudoCid);

                for (var identifier : encounter.getIdentifier()) {
                    if (identifier.hasSystem()
                            && identifier.getSystem().equals(fhirSystems.getEncounterId())) {
                        identifier.setValue(pseudoCid);
                    }
                }

                if (encounter.hasSubject()) {
                    var pseudoPid = pseudoPatIds.get(getIdFromReference(encounter.getSubject()));
                    encounter.getSubject().setReference("Patient/" + pseudoPid);
                }
            } else if (resource instanceof Observation) {
                var observation = (Observation) resource;

                if (observation.hasSubject()) {
                    var pseudoPid = pseudoPatIds.get(getIdFromReference(observation.getSubject()));
                    observation.getSubject().setReference("Patient/" + pseudoPid);
                }

                if (observation.hasEncounter()) {
                    var pseudoCid = pseudoCaseIds.get(getIdFromReference(observation.getEncounter()));
                    observation.getEncounter().setReference("Encounter/" + pseudoCid);
                }
            } else if (resource instanceof Procedure) {
                var procedure = (Procedure) resource;

                if (procedure.hasSubject()) {
                    var pseudoPid = pseudoPatIds.get(getIdFromReference(procedure.getSubject()));
                    procedure.getSubject().setReference("Patient/" + pseudoPid);
                }

                if (procedure.hasEncounter()) {
                    var pseudoCid = pseudoCaseIds.get(getIdFromReference(procedure.getEncounter()));
                    procedure.getEncounter().setReference("Encounter/" + pseudoCid);
                }
            } else if (resource instanceof Condition) {
                var condition = (Condition) resource;

                if (condition.hasSubject()) {
                    var pseudoPid = pseudoPatIds.get(getIdFromReference(condition.getSubject()));
                    condition.getSubject().setReference("Patient/" + pseudoPid);
                }

                if (condition.hasEncounter()) {
                    var pseudoCid = pseudoCaseIds.get(getIdFromReference(condition.getEncounter()));
                    condition.getEncounter().setReference("Encounter/" + pseudoCid);
                }
            } else if (resource instanceof DiagnosticReport) {
                var report = (DiagnosticReport) resource;

                var pseudoRepid = pseudoReportIds.get(report.getIdElement().getIdPart());
                report.setId(pseudoRepid);

                for (var identifier : report.getIdentifier()) {
                    if (identifier.hasSystem() && identifier.getSystem().equals(fhirSystems.getReportId())) {
                        identifier.setValue(pseudoRepid);
                    }
                }

                if (report.hasSubject()) {
                    var pseudoPid = pseudoPatIds.get(getIdFromReference(report.getSubject()));
                    report.getSubject().setReference("Patient/" + pseudoPid);
                }

                if (report.hasEncounter()) {
                    var pseudoCid = pseudoCaseIds.get(getIdFromReference(report.getEncounter()));
                    report.getEncounter().setReference("Encounter/" + pseudoCid);
                }
            } else if (resource instanceof MedicationStatement) {
                var medicationStatement = (MedicationStatement) resource;

                if (medicationStatement.hasSubject() && medicationStatement.getSubject().getReferenceElement().getResourceType().equals("Patient")) {
                    var pseudoPid = pseudoPatIds.get(getIdFromReference(medicationStatement.getSubject()));
                    medicationStatement.getSubject().setReference("Patient/" + pseudoPid);
                }

                if (medicationStatement.hasContext() && medicationStatement.getContext().getReferenceElement().getResourceType().equals("Encounter")) {
                    var pseudoCid = pseudoCaseIds.get(getIdFromReference(medicationStatement.getContext()));
                    medicationStatement.getContext().setReference("Encounter/" + pseudoCid);
                }
            }
        }

        return resources;
    }
}
