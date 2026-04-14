package com.demo.router;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.context.annotation.Bean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.function.Consumer;

/**
 * Boot + Function bean for the package-router.
 *
 * <p>SCS binds {@code documents.enriched} -> this {@code Consumer<EnrichedDocument>}
 * at runtime. The consumer delegates to {@link RoutingEngine#decide},
 * then uses a {@link StreamBridge} to publish the original document to
 * the variable output topic returned by the DMN. Using {@code Consumer}
 * rather than {@code Function} is required because SCS would otherwise
 * publish to a single fixed output binding — here we need the
 * destination to depend on the payload.
 */
@SpringBootApplication(scanBasePackages = { "com.demo.router", "com.demo.commons" })
@RestController
public class RouterApplication {

    private static final Logger log = LoggerFactory.getLogger(RouterApplication.class);

    private final RoutingEngine engine;
    private final AuditLedger ledger;
    private final StreamBridge bridge;

    public RouterApplication(RoutingEngine engine,
                             AuditLedger ledger,
                             StreamBridge bridge) {
        this.engine = engine;
        this.ledger = ledger;
        this.bridge = bridge;
    }

    public static void main(String[] args) {
        SpringApplication.run(RouterApplication.class, args);
    }

    /**
     * SCS binding: {@code documents.enriched} -> route() -> dynamic
     * output topic selected by the DMN.
     */
    @Bean
    public Consumer<EnrichedDocument> route() {
        return doc -> {
            try {
                RoutingDecision decision = engine.decide(doc);
                ledger.record(doc, decision);
                bridge.send(decision.outputTopic(), doc);
                log.info("router.sent topic={} correlationId={} rows={}",
                        decision.outputTopic(), doc.correlationId(), decision.decisionsFired());
            } catch (Exception e) {
                // Swallow and log — SCS would otherwise put the message
                // back on the topic indefinitely. In production we'd
                // send to a DLQ.
                log.error("router.failed correlationId={} error={}",
                        doc.correlationId(), e.getMessage(), e);
            }
        };
    }

    @GetMapping("/recent")
    public List<RecentRouting> recent() {
        return engine.recent();
    }
}
