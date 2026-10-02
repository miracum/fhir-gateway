package org.miracum.etl.fhirgateway.stores;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import ca.uhn.fhir.context.FhirContext;
import java.util.List;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Bundle.HTTPVerb;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Patient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.retry.support.RetryTemplate;

@ExtendWith(MockitoExtension.class)
class PostgresFhirResourceRepositoryTest {
  private static final FhirContext FHIR_CONTEXT = FhirContext.forR4();

  @Mock private JdbcTemplate jdbcTemplate;
  @Captor private ArgumentCaptor<List<Object[]>> rowsCaptor;

  @Test
  void save_withResourcesToStore_upsertsThemSortedById() {
    var bundle = new Bundle();
    addEntry(bundle, new Patient().setId("Patient/b"), HTTPVerb.PUT);
    addEntry(bundle, new Observation().setId("Observation/a"), HTTPVerb.POST);

    createRepository().save(bundle);

    verify(jdbcTemplate).batchUpdate(startsWith("INSERT INTO resources"), rowsCaptor.capture());
    var rows = rowsCaptor.getValue();
    assertThat(rows).extracting(row -> row[0]).containsExactly("a", "b");
    assertThat(rows).extracting(row -> row[1]).containsExactly("Observation", "Patient");
    assertThat((String) rows.get(1)[2]).contains("\"resourceType\":\"Patient\"");
  }

  @Test
  void save_withDeleteRequest_marksTheResourceAsDeleted() {
    var bundle = new Bundle();
    bundle.addEntry().getRequest().setMethod(HTTPVerb.DELETE).setUrl("Patient/123");

    createRepository().save(bundle);

    verify(jdbcTemplate).batchUpdate(startsWith("UPDATE resources"), rowsCaptor.capture());
    assertThat(rowsCaptor.getValue()).containsExactly(new Object[] {"Patient", "123"});
  }

  @Test
  void save_withEmptyBundle_doesNotTouchTheDatabase() {
    createRepository().save(new Bundle());

    verifyNoInteractions(jdbcTemplate);
  }

  private PostgresFhirResourceRepository createRepository() {
    return new PostgresFhirResourceRepository(FHIR_CONTEXT, jdbcTemplate, new RetryTemplate());
  }

  private static void addEntry(
      Bundle bundle, org.hl7.fhir.r4.model.Resource resource, HTTPVerb method) {
    bundle
        .addEntry()
        .setResource(resource)
        .getRequest()
        .setMethod(method)
        .setUrl(resource.getIdElement().getValue());
  }
}
