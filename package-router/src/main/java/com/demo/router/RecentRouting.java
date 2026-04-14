package com.demo.router;

import java.time.Instant;

/**
 * Small summary shown by {@code GET /recent}. The dashboard polls it
 * to render the live "last-N routings" table on the package-router
 * Service Card.
 */
public record RecentRouting(
        String id,
        Integer version,
        String correlationId,
        String title,
        String priority,
        String grade,
        String outputTopic,
        Integer slaMinutes,
        String routingReason,
        String routedAt
) {

    public static RecentRouting from(EnrichedDocument doc, RoutingDecision d) {
        return new RecentRouting(
                doc.id(),
                doc.schemaVersion(),
                doc.correlationId(),
                doc.title(),
                doc.priorityOrUnknown(),
                doc.gradeOrUnknown(),
                d.outputTopic(),
                d.slaMinutes(),
                d.routingReason(),
                Instant.now().toString()
        );
    }
}
