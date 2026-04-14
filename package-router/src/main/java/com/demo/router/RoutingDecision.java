package com.demo.router;

import java.util.List;

/**
 * The flattened output of one DMN evaluation.
 *
 * <p>Carries everything the router needs to act on a decision:
 * <ul>
 *   <li>{@code outputTopic} / {@code slaMinutes} / {@code routingReason}
 *       — the three outputs of the {@code packageRoute} decision.</li>
 *   <li>{@code decisionsFired} — the 1-based indices of DMN rows the
 *       runtime actually matched, captured by the
 *       {@link DmnAuditListener}. Enables downstream audit queries
 *       like "which rule fired for document X?".</li>
 * </ul>
 */
public record RoutingDecision(
        String outputTopic,
        Integer slaMinutes,
        String routingReason,
        List<Integer> decisionsFired
) {

    public static final String QUARANTINE_TOPIC = "packages.quarantine";

    public static RoutingDecision fallback(String reason) {
        return new RoutingDecision(QUARANTINE_TOPIC, null, reason, List.of(6));
    }
}
