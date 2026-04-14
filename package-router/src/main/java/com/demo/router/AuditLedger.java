package com.demo.router;

import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes per-decision audit records to MongoDB.
 *
 * <p>One document per evaluation lands in the {@code audit} collection
 * of the {@code documentsdb} database. Each record captures:
 * <ul>
 *   <li>the originating correlation id and document id</li>
 *   <li>the inputs the DMN saw</li>
 *   <li>the outputs the DMN produced (topic, sla, reason)</li>
 *   <li>the indices of DMN rows that fired
 *       ({@code decisionsFired})</li>
 * </ul>
 *
 * <p>{@link MongoTemplate} is optional so the bean can be reused on a
 * developer laptop without MongoDB by simply not providing the
 * dependency — calls become no-ops with a warning.
 */
@Component
public class AuditLedger {

    private static final Logger log = LoggerFactory.getLogger(AuditLedger.class);
    private static final String COLLECTION = "audit";

    private final MongoTemplate mongo;

    public AuditLedger(ObjectProvider<MongoTemplate> mongoProvider) {
        this.mongo = mongoProvider.getIfAvailable();
    }

    public void record(EnrichedDocument doc, RoutingDecision decision) {
        if (mongo == null) {
            log.warn("audit.skipped reason=no-mongo correlationId={}", doc.correlationId());
            return;
        }
        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("priority", doc.priorityOrUnknown());
        inputs.put("bodyLength", doc.bodyLength());
        inputs.put("enrichmentGrade", doc.gradeOrUnknown());

        Map<String, Object> outputs = new LinkedHashMap<>();
        outputs.put("outputTopic", decision.outputTopic());
        outputs.put("slaMinutes", decision.slaMinutes());
        outputs.put("routingReason", decision.routingReason());

        List<Integer> rowsFired = decision.decisionsFired() == null
                ? List.of()
                : decision.decisionsFired();

        Document record = new Document()
                .append("serviceName", "package-router")
                .append("documentId", doc.id())
                .append("correlationId", doc.correlationId())
                .append("recordedAt", Instant.now().toString())
                .append("inputs", inputs)
                .append("outputs", outputs)
                .append("decisionsFired", rowsFired);
        try {
            mongo.getCollection(COLLECTION).insertOne(record);
        } catch (Exception e) {
            // Audit failures must never crash the routing path.
            log.warn("audit.write.failed correlationId={} error={}",
                    doc.correlationId(), e.getMessage());
        }
    }
}
