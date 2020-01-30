package org.miracum.etl.fhirgateway.stores;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.IParser;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.miracum.etl.fhirgateway.controllers.FhirController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Component
public class PostgresFhirResourceRepository implements FhirResourceRepository {

    private static final Logger log = LoggerFactory.getLogger(FhirController.class);

    private final IParser fhirParser;
    private final JdbcTemplate dataSinkTemplate;

    @Autowired
    public PostgresFhirResourceRepository(FhirContext fhirContext, JdbcTemplate dataSinkTemplate) {
        this.fhirParser = fhirContext.newJsonParser();
        this.dataSinkTemplate = dataSinkTemplate;
    }

    @Override
    public void save(List<IBaseResource> resources) {
        var insertValues = resources.stream()
                .map(resource -> new Object[]{
                        resource.getIdElement().getIdPart(),
                        resource.fhirType(),
                        fhirParser.encodeResourceToString(resource)
                })
                .collect(Collectors.toCollection(ArrayList::new));

        dataSinkTemplate.batchUpdate(
                "INSERT INTO resources (fhir_id, type, data) VALUES (?, ?, ?::json) ON CONFLICT (fhir_id) DO UPDATE set data = EXCLUDED.data",
                insertValues);
    }

    @Override
    public void save(IBaseResource resource) {
        save(List.of(resource));
    }
}
