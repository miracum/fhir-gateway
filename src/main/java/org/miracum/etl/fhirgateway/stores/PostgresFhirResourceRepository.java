package org.miracum.etl.fhirgateway.stores;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.IParser;
import io.micrometer.core.instrument.Metrics;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.miracum.etl.fhirgateway.controllers.FhirController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.retry.RetryCallback;
import org.springframework.retry.RetryContext;
import org.springframework.retry.listener.RetryListenerSupport;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Component
public class PostgresFhirResourceRepository implements FhirResourceRepository {

    private static final Logger log = LoggerFactory.getLogger(FhirController.class);

    private static final AtomicInteger batchUpdateFailed =
            Metrics.globalRegistry.gauge("fhirgateway.postgres.batchupdate.errors.total", new AtomicInteger(0));

    private final IParser fhirParser;
    private final JdbcTemplate dataSinkTemplate;
    private RetryTemplate retryTemplate;

    @Autowired
    public PostgresFhirResourceRepository(FhirContext fhirContext, JdbcTemplate dataSinkTemplate, RetryTemplate retryTemplate) {
        this.fhirParser = fhirContext.newJsonParser();
        this.dataSinkTemplate = dataSinkTemplate;
        this.retryTemplate = retryTemplate;

        this.retryTemplate.registerListener(new RetryListenerSupport() {
            @Override
            public <T, E extends Throwable> void onError(RetryContext context, RetryCallback<T, E> callback, Throwable throwable) {
                log.warn("Trying to persist data caused error. {} attempt.", context.getRetryCount(), throwable);
                batchUpdateFailed.incrementAndGet();
            }
        });
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

        retryTemplate.execute((context) ->
                dataSinkTemplate.batchUpdate(
                        "INSERT INTO resources (fhir_id, type, data) VALUES (?, ?, ?::json) ON CONFLICT (fhir_id) DO UPDATE set data = EXCLUDED.data",
                        insertValues));
    }

    @Override
    public void save(IBaseResource resource) {
        save(List.of(resource));
    }
}
