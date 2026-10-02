package org.miracum.etl.fhirgateway.controllers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.uhn.fhir.context.FhirContext;
import java.util.Optional;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Bundle.HTTPVerb;
import org.hl7.fhir.r4.model.Patient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.miracum.etl.fhirgateway.processors.ResourcePipeline;
import org.miracum.etl.fhirgateway.stores.KafkaFhirResourceRepository;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class FhirControllerTest {
  private static final FhirContext FHIR_CONTEXT = FhirContext.forR4();

  @Mock private ResourcePipeline pipeline;
  @Mock private KafkaFhirResourceRepository kafkaStore;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc =
        MockMvcBuilders.standaloneSetup(
                new FhirController(FHIR_CONTEXT, pipeline, Optional.of(kafkaStore)))
            .build();
  }

  @Test
  void postFhirRoot_withBundle_returnsAndStoresTheProcessedBundle() throws Exception {
    var processed = new Bundle();
    processed.setId("processed");
    when(pipeline.process(any(Bundle.class))).thenReturn(processed);

    var bundle = new Bundle();
    bundle.addEntry().setResource(new Patient().setId("Patient/123"));

    mockMvc
        .perform(post("/fhir").content(encode(bundle)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value("processed"));

    verify(kafkaStore).save(processed);
  }

  @Test
  void postFhirRoot_withNonBundleResource_isRejected() throws Exception {
    mockMvc
        .perform(post("/fhir").content(encode(new Patient().setId("Patient/123"))))
        .andExpect(status().isBadRequest());

    verifyNoInteractions(pipeline, kafkaStore);
  }

  @Test
  void putResource_withSingleResource_isWrappedInATransactionBundle() throws Exception {
    when(pipeline.process(any(Bundle.class))).thenReturn(new Bundle());

    mockMvc
        .perform(put("/fhir/Patient/123").content(encode(new Patient().setId("Patient/123"))))
        .andExpect(status().isOk());

    var bundle = captureProcessedBundle();
    assertThat(bundle.getType()).isEqualTo(Bundle.BundleType.TRANSACTION);
    assertThat(bundle.getEntry()).hasSize(1);
    assertThat(bundle.getEntryFirstRep().getRequest().getMethod()).isEqualTo(HTTPVerb.PUT);
    assertThat(bundle.getEntryFirstRep().getResource()).isInstanceOf(Patient.class);
  }

  @Test
  void deleteResource_isProcessedAndStoredAsDeleteRequest() throws Exception {
    var processed = new Bundle();
    when(pipeline.process(any(Bundle.class))).thenReturn(processed);

    mockMvc.perform(delete("/fhir/Patient/123")).andExpect(status().isOk());

    var bundle = captureProcessedBundle();
    var request = bundle.getEntryFirstRep().getRequest();
    assertThat(request.getMethod()).isEqualTo(HTTPVerb.DELETE);
    assertThat(request.getUrl()).isEqualTo("Patient/123");
    verify(kafkaStore).save(processed);
  }

  @Test
  void getCapabilities_returnsTheCapabilityStatement() throws Exception {
    mockMvc
        .perform(get("/fhir/metadata"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.resourceType").value("CapabilityStatement"));
  }

  private Bundle captureProcessedBundle() {
    var captor = ArgumentCaptor.forClass(Bundle.class);
    verify(pipeline).process(captor.capture());
    return captor.getValue();
  }

  private static String encode(org.hl7.fhir.r4.model.Resource resource) {
    return FHIR_CONTEXT.newJsonParser().encodeResourceToString(resource);
  }
}
