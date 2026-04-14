package com.demo.enricher;

import com.demo.commons.observability.EventCatalogEntry;
import com.demo.commons.observability.TagDescriptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Wires the per-service event catalog and tag descriptors into the
 * Spring context so {@link com.demo.commons.observability.MeterEventAspect}
 * can resolve them by name.
 */
@Configuration
public class EnrichmentConfig {

    /**
     * Catalog bean keyed by {@link EnrichmentEvents} constant name
     * (e.g. {@code "DOCUMENT_ENRICHED"}) — the value the
     * {@code @MeterEvent} annotation refers to.
     */
    @Bean
    public Map<String, EventCatalogEntry> eventCatalog() {
        Map<String, EventCatalogEntry> map = new LinkedHashMap<>();
        for (EnrichmentEvents e : EnumSet.allOf(EnrichmentEvents.class)) {
            map.put(e.name(), e);
        }
        return map;
    }

    /** Tag descriptors the aspect iterates when building counter tags. */
    @Bean
    public List<TagDescriptor> tagDescriptors() {
        return List.of(EnrichmentTags.values());
    }
}
