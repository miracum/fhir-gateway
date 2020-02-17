package org.miracum.etl.fhirgateway;

import org.emau.icmvc.ganimed.ttp.psn.PSNManager;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class FhirGatewayApplicationTests {

    @MockBean
    private PSNManager psnManager;

    @Test
    void contextLoads() {
    }
}
