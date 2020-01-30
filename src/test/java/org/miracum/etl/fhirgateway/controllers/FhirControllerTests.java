package org.miracum.etl.fhirgateway.controllers;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.miracum.etl.fhirgateway.AppConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration(classes = {AppConfig.class})
@WebAppConfiguration
public class FhirControllerTests {

    @Autowired
    private WebApplicationContext wac;

    private MockMvc mockMvc;

    @Before
    public void setup() throws Exception {
        this.mockMvc = MockMvcBuilders.webAppContextSetup(this.wac).build();
    }

    @Test
    public void wacGetBean_inServletContext_shouldProvideFhirController() {
        var servletContext = wac.getServletContext();

        assertThat(servletContext).isNotNull();
        assertThat(servletContext).isInstanceOf(MockServletContext.class);
        assertThat(wac.getBean("FhirController")).isNotNull();
    }

    @Test
    public void put_toFhirRoot_shouldCauseClientError() throws Exception {
        var mvcResult = this.mockMvc.perform(put("/fhir"))
                .andDo(print()).andExpect(status().is4xxClientError())
                .andReturn();

        assertThat(mvcResult.getResponse().getContentType()).isEqualTo("application/json;charset=UTF-8");
    }
}
