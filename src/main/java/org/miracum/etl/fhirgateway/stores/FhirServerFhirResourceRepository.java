package org.miracum.etl.fhirgateway.stores;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.IParser;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import io.micrometer.core.instrument.Metrics;
import org.hl7.fhir.r4.model.Bundle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.retry.RetryCallback;
import org.springframework.retry.RetryContext;
import org.springframework.retry.backoff.FixedBackOffPolicy;
import org.springframework.retry.listener.RetryListenerSupport;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

@Component
public class FhirServerFhirResourceRepository implements FhirResourceRepository {

    private static final Logger log = LoggerFactory.getLogger(FhirServerFhirResourceRepository.class);

    private static final AtomicInteger saveFailedCounter =
            Metrics.globalRegistry.gauge("fhirgateway.fhir.server.batchupdate.errors.total", new AtomicInteger(0));

    private final IParser fhirParser;
    private final IGenericClient client;
    private final RetryTemplate retryTemplate;

    @Autowired
    public FhirServerFhirResourceRepository(FhirContext fhirContext,
                                            @Value("${services.fhirServer.url}") String fhirServerUrl) {
        this.fhirParser = fhirContext.newJsonParser();
        this.client = fhirContext.newRestfulGenericClient(fhirServerUrl);

        this.retryTemplate = new RetryTemplate();

        var fixedBackOffPolicy = new FixedBackOffPolicy();
        fixedBackOffPolicy.setBackOffPeriod(5_000);
        retryTemplate.setBackOffPolicy(fixedBackOffPolicy);

        retryTemplate.setRetryPolicy(new SimpleRetryPolicy(5));

        this.retryTemplate.registerListener(new RetryListenerSupport() {
            @Override
            public <T, E extends Throwable> void onError(RetryContext context, RetryCallback<T, E> callback, Throwable throwable) {
                log.warn("Trying to persist data caused error. {} attempt.", context.getRetryCount(), throwable);
                saveFailedCounter.incrementAndGet();
            }
        });
    }

    @Override
    public void save(Bundle bundle) {
        log.debug("Sending bundle {} with contents {}",
                bundle,
                fhirParser.encodeResourceToString(bundle));

        var response = retryTemplate.execute(context -> client.transaction().withBundle(bundle).execute());

        log.debug("Response for bundle {} with contents {}",
                fhirParser.encodeResourceToString(bundle),
                fhirParser.encodeResourceToString(response));
    }
}
