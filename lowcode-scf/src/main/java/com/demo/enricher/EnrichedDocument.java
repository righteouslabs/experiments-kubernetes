package com.demo.enricher;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.Map;

/**
 * Outbound enriched record. Carries every field of the inbound
 * {@link Document} plus the quality annotations produced by
 * {@code enrich()}.
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
        int qualityScore,
        String inspectionGrade,
        String processedBy,
        String enrichedAt
) {

    public static final String PROCESSED_BY = "document-enricher";

    /**
     * Build an {@link EnrichedDocument} from a typed input {@link Document}
     * plus the computed score/grade. Keeps the Function body declarative.
     */
    public static EnrichedDocument from(Document doc, int score, String grade) {
        return new EnrichedDocument(
                doc.id(),
                doc.schemaVersion(),
                doc.title(),
                doc.body(),
                doc.createdAt(),
                doc.correlationId(),
                doc.author(),
                doc.tags(),
                doc.priority(),
                doc.metadata(),
                score,
                grade,
                PROCESSED_BY,
                Instant.now().toString()
        );
    }
}
