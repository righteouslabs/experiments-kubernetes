package com.demo.enricher;

import com.demo.commons.observability.TagDescriptor;

/**
 * Tag keys the aspect attaches to {@link EnrichmentEvents} counters.
 * Each name matches a {@link com.demo.commons.observability.ProcessContext}
 * attribute key the {@link EnrichmentEngine} writes during processing.
 */
public enum EnrichmentTags implements TagDescriptor {

    GRADE("grade"),
    SCHEMA_VERSION("schemaVersion"),
    PRIORITY("priority");

    private final String tagName;

    EnrichmentTags(String tagName) {
        this.tagName = tagName;
    }

    @Override
    public String tagName() {
        return tagName;
    }
}
