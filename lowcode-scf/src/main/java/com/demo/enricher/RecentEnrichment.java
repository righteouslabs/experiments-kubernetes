package com.demo.enricher;

/**
 * Slim summary row surfaced by {@code GET /recent}. The dashboard
 * reads a handful of these to render the Low-Code Stage trace table;
 * keeping it typed makes the contract explicit.
 */
public record RecentEnrichment(
        String id,
        Integer version,
        String correlationId,
        String title,
        int qualityScore,
        String grade,
        String enrichedAt
) {

    public static RecentEnrichment from(EnrichedDocument e) {
        return new RecentEnrichment(
                e.id(),
                e.schemaVersion(),
                e.correlationId(),
                e.title(),
                e.qualityScore(),
                e.inspectionGrade(),
                e.enrichedAt()
        );
    }
}
