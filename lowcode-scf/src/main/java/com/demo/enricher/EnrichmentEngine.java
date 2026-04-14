package com.demo.enricher;

import com.demo.commons.observability.BusinessRule;
import com.demo.commons.observability.MeterEvent;
import com.demo.commons.observability.ProcessContext;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * The actual per-message work. Kept in its own Spring bean so the AOP
 * proxy can intercept {@link #apply(Document)} on every invocation —
 * decorating it with the Micrometer counter + structured log emitted
 * by {@link com.demo.commons.observability.MeterEventAspect}.
 *
 * <p>The {@link BusinessRule} annotations on {@link #apply(Document)}
 * are pure metadata: the {@code ServiceCardController} reflects them
 * to render the Decision Rules table for the PO. They have zero
 * runtime effect — the arithmetic lives in the two small helper
 * methods below.
 */
@Service
public class EnrichmentEngine {

    private static final int RECENT_BUFFER_SIZE = 20;

    private final Timer enrichTimer;
    private final ArrayDeque<RecentEnrichment> recent = new ArrayDeque<>(RECENT_BUFFER_SIZE);
    private final Object recentLock = new Object();

    public EnrichmentEngine(MeterRegistry meterRegistry) {
        this.enrichTimer = Timer.builder("enrichment.latency")
                .description("Enrichment processing latency")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry);
    }

    /**
     * Read this like a sentence: open a context with the correlation
     * id, compute score + grade, publish the enriched record. The
     * aspect adds the counter and the log; the SCS binder adds Kafka
     * delivery.
     */
    @MeterEvent("DOCUMENT_ENRICHED")
    @BusinessRule(condition = "score >= 75",               outcome = "grade = A")
    @BusinessRule(condition = "score >= 50 && score < 75", outcome = "grade = B")
    @BusinessRule(condition = "score < 50",                outcome = "grade = C")
    @BusinessRule(condition = "priority == HIGH",          outcome = "score +20")
    @BusinessRule(condition = "priority == LOW",           outcome = "score -10")
    @BusinessRule(condition = "tags non-empty",            outcome = "score +10")
    public EnrichedDocument apply(Document doc) {
        return enrichTimer.record(() -> {
            try (var ctx = ProcessContext.open(doc.correlationId())) {
                int score = scoreFor(doc);
                String grade = gradeFor(score);
                ctx.put(EnrichmentTags.GRADE.tagName(), grade);
                ctx.put(EnrichmentTags.SCHEMA_VERSION.tagName(),
                        String.valueOf(doc.schemaVersionOr(0)));
                ctx.put(EnrichmentTags.PRIORITY.tagName(), doc.priorityOrDefault());
                EnrichedDocument out = EnrichedDocument.from(doc, score, grade);
                rememberRecent(RecentEnrichment.from(out));
                return out;
            }
        });
    }

    /** Deterministic quality score in [0, 100]. */
    static int scoreFor(Document doc) {
        int priorityBoost = switch (doc.priorityOrDefault()) {
            case "HIGH" -> 20;
            case "LOW" -> -10;
            default -> 0;
        };
        int base = Math.min(80, doc.bodyLength() / 3)
                + (doc.hasTags() ? 10 : 0)
                + priorityBoost;
        return Math.max(0, Math.min(100, base));
    }

    /** A for ≥75, B for ≥50, else C. */
    static String gradeFor(int score) {
        return score >= 75 ? "A" : score >= 50 ? "B" : "C";
    }

    private void rememberRecent(RecentEnrichment row) {
        synchronized (recentLock) {
            if (recent.size() >= RECENT_BUFFER_SIZE) recent.pollLast();
            recent.addFirst(row);
        }
    }

    public List<RecentEnrichment> recent() {
        synchronized (recentLock) {
            return new ArrayList<>(recent);
        }
    }
}
