package com.demo.enricher;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.function.Function;

/**
 * Application bootstrap + Function bean.
 *
 * <p>AsyncAPI is the source of truth for the contract; this class
 * wires SCS to the per-message worker bean {@link EnrichmentEngine}.
 * The Spring Cloud Stream Kafka binder handles all I/O via
 * {@code application.yml}; there is <strong>no</strong> Kafka client
 * code here.
 *
 * <p>Putting the actual work on a separate bean is what lets the
 * {@link com.demo.commons.observability.MeterEventAspect} intercept
 * every call — the AOP proxy wraps
 * {@link EnrichmentEngine#apply(Document)}, not this lambda.
 */
@SpringBootApplication(scanBasePackages = { "com.demo.enricher", "com.demo.commons" })
@RestController
public class EnricherApplication {

    private final EnrichmentEngine engine;

    public EnricherApplication(EnrichmentEngine engine) {
        this.engine = engine;
    }

    public static void main(String[] args) {
        SpringApplication.run(EnricherApplication.class, args);
    }

    /** SCS binder: `documents` -> enrich() -> `documents.enriched`. */
    @Bean
    public Function<Document, EnrichedDocument> enrich() {
        return engine::apply;
    }

    @GetMapping("/recent")
    public List<RecentEnrichment> recent() {
        return engine.recent();
    }
}
