package com.demo.router;

import com.demo.commons.observability.EventCatalogEntry;

/**
 * Event catalog for the package-router service. One constant per
 * {@code @MeterEvent}-annotated method. The aspect on
 * {@link RoutingEngine#decide} increments
 * {@code package.routed} — tagged by topic, priority, grade and
 * routing_reason so Grafana can slice by any combination.
 */
public enum RoutingEvents implements EventCatalogEntry {

    PACKAGE_ROUTED(
            "package.routed",
            "observability.routing.package-routed.log.enabled",
            "observability.routing.package-routed.metric.enabled",
            true);

    private final String metricName;
    private final String logToggleKey;
    private final String metricToggleKey;
    private final boolean auditable;

    RoutingEvents(String metricName,
                  String logToggleKey,
                  String metricToggleKey,
                  boolean auditable) {
        this.metricName = metricName;
        this.logToggleKey = logToggleKey;
        this.metricToggleKey = metricToggleKey;
        this.auditable = auditable;
    }

    @Override public String eventName() { return name(); }
    @Override public String metricName() { return metricName; }
    @Override public String logToggleKey() { return logToggleKey; }
    @Override public String metricToggleKey() { return metricToggleKey; }
    @Override public boolean auditable() { return auditable; }
}
