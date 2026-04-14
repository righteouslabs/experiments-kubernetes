package com.demo.router;

import com.demo.commons.observability.EventCatalogEntry;
import com.demo.commons.observability.TagDescriptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Exposes the per-service event catalog and tag descriptors so
 * {@link com.demo.commons.observability.MeterEventAspect} can resolve
 * {@code @MeterEvent} references.
 */
@Configuration
public class RoutingConfig {

    @Bean
    public Map<String, EventCatalogEntry> eventCatalog() {
        Map<String, EventCatalogEntry> map = new LinkedHashMap<>();
        for (RoutingEvents e : EnumSet.allOf(RoutingEvents.class)) {
            map.put(e.name(), e);
        }
        return map;
    }

    @Bean
    public List<TagDescriptor> tagDescriptors() {
        return List.of(RoutingTags.values());
    }
}
