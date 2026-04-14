package com.demo.router;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Map;

/**
 * The enriched record the package-router reads off the
 * {@code documents.enriched} Kafka topic. Shape mirrors the output of
 * the Phase 1 document-enricher — everything from the upstream
 * document plus {@code qualityScore} / {@code inspectionGrade}.
 *
 * <p>Tolerant of unknown fields so forward-compatible enricher schemas
 * continue to flow through without recompilation.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EnrichedDocument(
        String id,
        Integer schemaVersion,
        String title,
        String body,
        String createdAt,
        String correlationId,
        String author,
        Object tags,
        String priority,
        Map<String, Object> metadata,
        Integer qualityScore,
        String inspectionGrade,
        String processedBy,
        String enrichedAt
) {

    public int bodyLength() {
        return body == null ? 0 : body.length();
    }

    public String priorityOrUnknown() {
        return priority == null || priority.isBlank() ? "UNKNOWN" : priority;
    }

    public String gradeOrUnknown() {
        return inspectionGrade == null || inspectionGrade.isBlank() ? "UNKNOWN" : inspectionGrade;
    }
}
