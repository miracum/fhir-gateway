package org.miracum.etl.fhirgateway;

import ca.uhn.fhir.context.FhirContext;
import io.micrometer.core.instrument.MeterRegistry;
import org.emau.icmvc.ganimed.ttp.psn.PSNManager;
import org.miracum.etl.fhirgateway.processors.AbstractPseudonymizer;
import org.miracum.etl.fhirgateway.processors.GpasPseudonymizer;
import org.miracum.etl.fhirgateway.processors.NullPseudonymizer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.autoconfigure.metrics.MeterRegistryCustomizer;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.backoff.FixedBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.RestTemplate;

import javax.xml.namespace.QName;
import javax.xml.ws.Service;
import java.net.MalformedURLException;
import java.net.URL;

@Configuration
public class AppConfig {

    @Bean
    public RetryTemplate retryTemplate() {
        var retryTemplate = new RetryTemplate();

        var fixedBackOffPolicy = new FixedBackOffPolicy();
        fixedBackOffPolicy.setBackOffPeriod(5_000);
        retryTemplate.setBackOffPolicy(fixedBackOffPolicy);

        var retryPolicy = new SimpleRetryPolicy();
        retryPolicy.setMaxAttempts(5);
        retryTemplate.setRetryPolicy(retryPolicy);

        return retryTemplate;
    }

    @Bean
    public FhirContext fhirContext() {
        return FhirContext.forR4();
    }

    @Bean
    public MeterRegistryCustomizer<MeterRegistry> injectAppLabel() {
        return registry -> registry.config().commonTags("appname", "fhir-gateway");
    }

    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplateBuilder().build();
    }

    @Bean
    public AbstractPseudonymizer pseudonymizer(
            @Value("${services.gpas.enabled}") boolean isGpasEnabled,
            @Value("${services.gpas.api.url}") String gpasApiUrl,
            RetryTemplate retryTemplate,
            FhirSystemsConfig fhirSystems) throws MalformedURLException {
        if (isGpasEnabled) {
            var gpasServiceName =
                    new QName("http://psn.ttp.ganimed.icmvc.emau.org/", "PSNManagerBeanService");
            var wsdlUrlGpas = new URL(gpasApiUrl);

            var serviceGpas = retryTemplate.execute(ctx -> Service.create(wsdlUrlGpas, gpasServiceName));
            var psnManager = serviceGpas.getPort(PSNManager.class);
            return new GpasPseudonymizer(psnManager, fhirSystems);
        } else {
            return new NullPseudonymizer();
        }
    }
}
