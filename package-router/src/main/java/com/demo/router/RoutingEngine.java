package com.demo.router;

import com.demo.commons.observability.BusinessRule;
import com.demo.commons.observability.MeterEvent;
import com.demo.commons.observability.ProcessContext;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import org.kie.dmn.api.core.DMNContext;
import org.kie.dmn.api.core.DMNModel;
import org.kie.dmn.api.core.DMNResult;
import org.kie.dmn.api.core.DMNRuntime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Per-message routing work.
 *
 * <p>Opens a {@link ProcessContext} keyed on the document's
 * correlation id, populates the three DMN inputs, evaluates the
 * {@code package-routing} model, and pulls out the three outputs. The
 * {@link DmnAuditListener} records which rows fired.
 *
 * <p>The {@link BusinessRule} annotations on {@link #decide} are pure
 * metadata — they mirror the 6 rows of the DMN table and are reflected
 * by {@code ServiceCardController} so the PO sees the same rules
 * whether they look at the DMN file or the Service Card.
 */
@Service
public class RoutingEngine {

    private static final Logger log = LoggerFactory.getLogger(RoutingEngine.class);
    private static final int RECENT_BUFFER_SIZE = 20;
    private static final String DMN_NAMESPACE = "https://demo.com/dmn/package-routing";
    private static final String DMN_NAME = "package-routing";

    private final DMNRuntime dmnRuntime;
    private final DmnAuditListener auditListener;
    private final Timer routingTimer;
    private final ArrayDeque<RecentRouting> recent = new ArrayDeque<>(RECENT_BUFFER_SIZE);
    private final Object recentLock = new Object();
    private DMNModel model;

    public RoutingEngine(DMNRuntime dmnRuntime,
                         DmnAuditListener auditListener,
                         MeterRegistry meterRegistry) {
        this.dmnRuntime = dmnRuntime;
        this.auditListener = auditListener;
        this.routingTimer = Timer.builder("routing.latency")
                .description("Package routing DMN evaluation latency")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry);
    }

    @PostConstruct
    void wireModel() {
        // Register the Spring-managed audit listener with the shared
        // runtime. Kogito's autoconfiguration also does this via bean
        // discovery, but doing it explicitly here makes the wiring
        // obvious and is a no-op on duplicate registration.
        dmnRuntime.addListener(auditListener);
        this.model = dmnRuntime.getModel(DMN_NAMESPACE, DMN_NAME);
        if (model == null) {
            log.error("DMN model '{}' not found in runtime — routes will fall back to quarantine",
                    DMN_NAME);
        } else {
            log.info("DMN model '{}' loaded from namespace {} ({} decisions)",
                    DMN_NAME, DMN_NAMESPACE, model.getDecisions().size());
        }
    }

    @MeterEvent("PACKAGE_ROUTED")
    @BusinessRule(condition = "priority = HIGH, any body, grade = A",
                  outcome = "topic = packages.express, sla = 5 min",
                  kpi = "package.routed{topic=packages.express}")
    @BusinessRule(condition = "priority = HIGH, body > 500, grade in (B, C)",
                  outcome = "topic = packages.express, sla = 10 min",
                  kpi = "package.routed{topic=packages.express}")
    @BusinessRule(condition = "priority = NORMAL, body < 200, grade in (A, B)",
                  outcome = "topic = packages.standard, sla = 30 min",
                  kpi = "package.routed{topic=packages.standard}")
    @BusinessRule(condition = "priority = NORMAL, body >= 200, grade in (A, B, C)",
                  outcome = "topic = packages.bulk, sla = 60 min",
                  kpi = "package.routed{topic=packages.bulk}")
    @BusinessRule(condition = "priority = LOW, any body, any grade",
                  outcome = "topic = packages.bulk, sla = 240 min",
                  kpi = "package.routed{topic=packages.bulk}")
    @BusinessRule(condition = "no other rule matched",
                  outcome = "topic = packages.quarantine, needs-review",
                  kpi = "package.routed{topic=packages.quarantine}")
    public RoutingDecision decide(EnrichedDocument doc) {
        return routingTimer.record(() -> {
            try (var ctx = ProcessContext.open(doc.correlationId())) {
                RoutingDecision decision = evaluateDmn(doc);
                ctx.put(RoutingTags.TOPIC.tagName(), decision.outputTopic());
                ctx.put(RoutingTags.PRIORITY.tagName(), doc.priorityOrUnknown());
                ctx.put(RoutingTags.GRADE.tagName(), doc.gradeOrUnknown());
                ctx.put(RoutingTags.ROUTING_REASON.tagName(),
                        decision.routingReason() == null ? "unknown" : decision.routingReason());
                rememberRecent(RecentRouting.from(doc, decision));
                return decision;
            }
        });
    }

    private RoutingDecision evaluateDmn(EnrichedDocument doc) {
        if (model == null) {
            return RoutingDecision.fallback("DMN model not loaded");
        }
        DMNContext context = dmnRuntime.newContext();
        context.set("priority", doc.priorityOrUnknown());
        // FEEL arithmetic prefers numbers; use long so it flows through cleanly.
        context.set("bodyLength", (long) doc.bodyLength());
        context.set("enrichmentGrade", doc.gradeOrUnknown());

        DMNResult result;
        try {
            result = dmnRuntime.evaluateAll(model, context);
        } catch (Exception e) {
            log.warn("dmn.evaluate.error correlationId={} error={}",
                    doc.correlationId(), e.getMessage());
            return RoutingDecision.fallback("DMN evaluation threw: " + e.getClass().getSimpleName());
        }

        List<Integer> fired = new ArrayList<>(auditListener.drainMatches());

        Object raw = result.getContext().get("packageRoute");
        if (raw instanceof Map<?, ?> map) {
            String topic = asString(map.get("outputTopic"), RoutingDecision.QUARANTINE_TOPIC);
            Integer sla = asInteger(map.get("slaMinutes"));
            String reason = asString(map.get("routingReason"), "Fallback - needs review");
            // If the listener didn't receive a decision-table event at
            // all (e.g. DMN model issue), assume the quarantine fallback
            // fired.
            if (fired.isEmpty()) fired.add(6);
            return new RoutingDecision(topic, sla, reason, fired);
        }
        return RoutingDecision.fallback("DMN result missing packageRoute");
    }

    private static String asString(Object value, String fallback) {
        return value == null ? fallback : value.toString();
    }

    private static Integer asInteger(Object value) {
        if (value == null) return null;
        if (value instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void rememberRecent(RecentRouting row) {
        synchronized (recentLock) {
            if (recent.size() >= RECENT_BUFFER_SIZE) recent.pollLast();
            recent.addFirst(row);
        }
    }

    public List<RecentRouting> recent() {
        synchronized (recentLock) {
            return new ArrayList<>(recent);
        }
    }
}
