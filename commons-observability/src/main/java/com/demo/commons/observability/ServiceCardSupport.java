package com.demo.commons.observability;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared helpers for rendering the JSON blocks on each service's
 * {@code /servicecard.json} endpoint.
 *
 * <p>Both the document-enricher and the package-router expose the same
 * three introspection sections — {@code rules}, {@code kpis}, and
 * {@code wiring} — driven by the annotations that live in this library.
 * Keeping the projection logic here means a new service only writes its
 * service-specific sections (pipeline, samples, DMN table…) and reuses
 * these verbatim.
 */
public final class ServiceCardSupport {

    private ServiceCardSupport() {}

    // --- rules --------------------------------------------------------

    /**
     * Reflects the {@link BusinessRule} / {@link BusinessRules}
     * annotations on the {@code @MeterEvent}-carrying method of the
     * supplied engine class into a flat list of {@code {condition,
     * outcome, kpi}} maps.
     *
     * <p>If no matching method is found (e.g. in a test without a
     * wired engine), returns an empty list.
     */
    public static List<Map<String, String>> rules(Class<?> engineClass) {
        List<Map<String, String>> out = new ArrayList<>();
        Method method = findAnnotatedMethod(engineClass);
        if (method == null) return out;

        BusinessRule single = method.getAnnotation(BusinessRule.class);
        if (single != null) out.add(ruleRow(single));
        BusinessRules many = method.getAnnotation(BusinessRules.class);
        if (many != null) {
            for (BusinessRule r : many.value()) out.add(ruleRow(r));
        }
        return out;
    }

    private static Map<String, String> ruleRow(BusinessRule r) {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("condition", r.condition());
        row.put("outcome", r.outcome());
        row.put("kpi", r.kpi());
        return row;
    }

    private static Method findAnnotatedMethod(Class<?> cls) {
        for (Method m : cls.getDeclaredMethods()) {
            if (m.isAnnotationPresent(MeterEvent.class)
                    && (m.isAnnotationPresent(BusinessRule.class)
                            || m.isAnnotationPresent(BusinessRules.class))) {
                return m;
            }
        }
        return null;
    }

    // --- kpis ---------------------------------------------------------

    /**
     * Project the registered event catalog plus any matching meters
     * already in the registry (filtered by {@code metricPrefix}) into
     * the flat list the Service Card renders as the KPI table.
     */
    public static List<Map<String, Object>> kpis(MeterEventAspect aspect,
                                                 MeterRegistry registry,
                                                 String metricPrefix) {
        List<Map<String, Object>> out = new ArrayList<>();

        List<String> tagNames = new ArrayList<>();
        for (TagDescriptor td : aspect.tagDescriptors()) tagNames.add(td.tagName());

        // 1. Catalog entries — declared counters.
        for (EventCatalogEntry e : aspect.catalogEntries()) {
            Map<String, Object> k = new LinkedHashMap<>();
            k.put("name", e.eventName());
            k.put("metricName", e.metricName());
            k.put("type", "counter");
            k.put("tags", tagNames);
            k.put("auditable", e.auditable());
            out.add(k);
        }

        // 2. Any prefix-matching meter the registry has seen (e.g. Timers).
        Collection<Meter> meters = registry.getMeters();
        for (Meter meter : meters) {
            String name = meter.getId().getName();
            if (!name.startsWith(metricPrefix)) continue;
            boolean alreadyListed = out.stream()
                    .anyMatch(row -> name.equals(row.get("metricName")));
            if (alreadyListed) continue;
            Map<String, Object> k = new LinkedHashMap<>();
            k.put("name", name);
            k.put("metricName", name);
            k.put("type", meter.getId().getType().name().toLowerCase());
            k.put("tags", List.of());
            out.add(k);
        }
        return out;
    }

    // --- wiring -------------------------------------------------------

    /** Standard Actuator + Service Card endpoints every service exposes. */
    public static final List<String> DEFAULT_REST_ENDPOINTS = List.of(
            "GET /recent",
            "GET /servicecard.json",
            "GET /actuator/health",
            "GET /actuator/prometheus"
    );

    /** Build a {@code {consumes, produces, rest}} wiring block. */
    public static Map<String, Object> wiring(List<String> consumes,
                                             List<String> produces,
                                             List<String> rest) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("consumes", consumes);
        out.put("produces", produces);
        out.put("rest", rest);
        return out;
    }
}
