package com.demo.router;

import org.kie.dmn.api.core.event.AfterEvaluateDecisionEvent;
import org.kie.dmn.api.core.event.AfterEvaluateDecisionTableEvent;
import org.kie.dmn.api.core.event.DMNRuntimeEventListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Listens for DMN runtime events and captures which rows of the
 * decision table actually fired for each evaluation.
 *
 * <p>The Kogito DMN starter auto-detects any Spring-managed bean
 * implementing {@link DMNRuntimeEventListener} and registers it with
 * the shared {@code DMNRuntime} at startup, so no manual wiring is
 * required.
 *
 * <p>Results are stashed against the current thread, then harvested
 * by {@link RoutingEngine} right after {@code evaluateAll()} returns.
 * The thread-local is cleared after harvest, keeping the listener
 * stateless across requests.
 */
@Component
public class DmnAuditListener implements DMNRuntimeEventListener {

    private static final Logger log = LoggerFactory.getLogger(DmnAuditListener.class);

    /**
     * Per-thread accumulator. Concurrent map keyed by thread id so a
     * test that stubs a fake thread id still gets isolated results.
     */
    private final ConcurrentMap<Long, List<Integer>> perThread = new ConcurrentHashMap<>();

    @Override
    public void afterEvaluateDecisionTable(AfterEvaluateDecisionTableEvent event) {
        List<Integer> matched = new ArrayList<>();
        // Apache KIE exposes matches as List<Integer> (1-based indices);
        // older/newer variants have used String identifiers. Accept both.
        for (Object raw : event.getMatches()) {
            Integer parsed = asRowIndex(raw);
            if (parsed != null) matched.add(parsed);
        }
        // Kogito emits row ids as 1-based strings in getMatches(). Keep
        // them as-is so they line up with the rule numbers rendered on
        // the Service Card.
        if (!matched.isEmpty()) {
            perThread.computeIfAbsent(Thread.currentThread().getId(), k -> new ArrayList<>())
                    .addAll(matched);
            log.info("dmn.decisionTable table={} matches={}",
                    event.getDecisionTableName(), matched);
        } else {
            log.info("dmn.decisionTable table={} no-rules-matched", event.getDecisionTableName());
        }
    }

    @Override
    public void afterEvaluateDecision(AfterEvaluateDecisionEvent event) {
        if (log.isDebugEnabled()) {
            log.debug("dmn.decision name={} result={}",
                    event.getDecision().getName(),
                    event.getResult().getDecisionResults());
        }
    }

    /**
     * Harvest and clear matches recorded during the current thread's
     * most recent {@code evaluateAll()}.
     */
    public List<Integer> drainMatches() {
        List<Integer> drained = perThread.remove(Thread.currentThread().getId());
        return drained == null ? Collections.emptyList() : drained;
    }

    private static Integer asRowIndex(Object raw) {
        if (raw == null) return null;
        if (raw instanceof Integer i) return i;
        if (raw instanceof Number n) return n.intValue();
        String s = raw.toString();
        if (s == null || s.isBlank()) return null;
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            // Some runtimes emit rule ids like "rule_3". Extract the
            // trailing digits if present.
            int i = s.length() - 1;
            while (i >= 0 && Character.isDigit(s.charAt(i))) i--;
            if (i < s.length() - 1) {
                try {
                    return Integer.parseInt(s.substring(i + 1));
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
            return null;
        }
    }
}
