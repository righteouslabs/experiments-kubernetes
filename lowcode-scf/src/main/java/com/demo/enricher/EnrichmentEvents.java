package com.demo.enricher;

import com.demo.commons.observability.EventCatalogEntry;

/**
 * Event catalog for the document-enricher. One constant per
 * {@code @MeterEvent}-annotated method. The metric name
 * {@code enrichment.outcome} is preserved verbatim so the existing
 * Grafana dashboard query continues to work without provisioning
 * changes.
 */
public enum EnrichmentEvents implements EventCatalogEntry {

    DOCUMENT_ENRICHED(
            "enrichment.outcome",
            "observability.enrichment.document-enriched.log.enabled",
            "observability.enrichment.document-enriched.metric.enabled",
            true);

    private final String metricName;
    private final String logToggleKey;
    private final String metricToggleKey;
    private final boolean auditable;

    EnrichmentEvents(String metricName,
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
