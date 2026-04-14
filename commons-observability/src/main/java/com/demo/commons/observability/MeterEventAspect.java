package com.demo.commons.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import jakarta.annotation.PostConstruct;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.AfterThrowing;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Advice that fires around any method annotated with {@link MeterEvent}
 * or {@link Tick}, increments the matching Micrometer counters, and
 * emits a structured log line — all gated behind per-event feature
 * toggles read from the Spring {@link Environment}.
 *
 * <p>The aspect looks up the event definition at startup (not on every
 * call) via the injected {@code Map<String, EventCatalogEntry>} bean.
 * Unknown event names fail fast in {@link #validateCatalog()} rather
 * than silently dropping metrics at runtime.
 *
 * <p><strong>Tag-cardinality guard:</strong> each tag declared via
 * {@link TagDescriptor} may also publish an allow-list of permitted
 * values via the property
 * {@code observability.tags.<tag-name>.allow} (comma-separated). If a
 * {@link ProcessContext} attribute falls outside that list the aspect
 * substitutes the literal value {@code "OTHER"}. This prevents a bad
 * input (e.g. a runaway correlation id used as a tag) from blowing up
 * the metrics cardinality.
 */
@Aspect
@Component
public class MeterEventAspect {

    private static final Logger log = LoggerFactory.getLogger(MeterEventAspect.class);
    private static final String FALLBACK_TAG_VALUE = "OTHER";

    private final MeterRegistry meterRegistry;
    private final Environment environment;
    private final Map<String, EventCatalogEntry> catalog;
    private final List<TagDescriptor> tagDescriptors;

    /** Cache of validated allow-lists keyed by tag name. */
    private final Map<String, Set<String>> tagAllowLists = new HashMap<>();

    public MeterEventAspect(MeterRegistry meterRegistry,
                            Environment environment,
                            ObjectProvider<Map<String, EventCatalogEntry>> catalogProvider,
                            ObjectProvider<List<TagDescriptor>> tagDescriptorProvider) {
        this.meterRegistry = meterRegistry;
        this.environment = environment;
        // ObjectProvider keeps the aspect usable in tests that omit the
        // per-service enum wiring — fall back to empty collections there.
        Map<String, EventCatalogEntry> found = catalogProvider.getIfAvailable();
        this.catalog = found == null ? Collections.emptyMap() : found;
        List<TagDescriptor> tags = tagDescriptorProvider.getIfAvailable();
        this.tagDescriptors = tags == null ? Collections.emptyList() : tags;
    }

    @PostConstruct
    void validateCatalog() {
        // Pre-compute allow-lists for each declared tag.
        for (TagDescriptor td : tagDescriptors) {
            String prop = "observability.tags." + td.tagName().toLowerCase() + ".allow";
            String raw = environment.getProperty(prop);
            if (raw == null || raw.isBlank()) {
                tagAllowLists.put(td.tagName(), Collections.emptySet());
                continue;
            }
            Set<String> allow = new HashSet<>();
            for (String part : raw.split(",")) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty()) allow.add(trimmed);
            }
            tagAllowLists.put(td.tagName(), Collections.unmodifiableSet(allow));
        }
        log.info("MeterEventAspect ready: {} catalog entries, {} tag descriptors",
                catalog.size(), tagDescriptors.size());
    }

    // --- @MeterEvent advice --------------------------------------------

    @AfterReturning("@annotation(meterEvent)")
    public void onMeterEventSuccess(JoinPoint jp, MeterEvent meterEvent) {
        EventCatalogEntry entry = resolve(meterEvent.value(), jp);
        if (entry == null) return;

        Tags tags = collectTags();

        if (isEnabled(entry.metricToggleKey(), true)) {
            meterRegistry.counter(entry.metricName(), tags).increment();
        }
        if (isEnabled(entry.logToggleKey(), true)) {
            emitStructuredLog(entry, tags, null);
        }
    }

    @AfterThrowing(pointcut = "@annotation(meterEvent)", throwing = "ex")
    public void onMeterEventError(JoinPoint jp, MeterEvent meterEvent, Throwable ex) {
        EventCatalogEntry entry = resolve(meterEvent.value(), jp);
        if (entry == null) return;

        Tags tags = collectTags();

        if (isEnabled(entry.metricToggleKey(), true)) {
            // Paired error counter: metricName + ".error"
            meterRegistry.counter(entry.metricName() + ".error", tags).increment();
        }
        if (isEnabled(entry.logToggleKey(), true)) {
            emitStructuredLog(entry, tags, ex);
        }
    }

    // --- @Tick advice --------------------------------------------------

    @AfterReturning("@annotation(tick)")
    public void onTick(JoinPoint jp, Tick tick) {
        for (String name : tick.names()) {
            if (name == null || name.isBlank()) continue;
            meterRegistry.counter(name).increment();
        }
    }

    // --- internals -----------------------------------------------------

    private EventCatalogEntry resolve(String eventName, JoinPoint jp) {
        EventCatalogEntry entry = catalog.get(eventName);
        if (entry == null) {
            log.warn("Unknown MeterEvent name '{}' on {} — no catalog entry",
                    eventName, jp.getSignature().toShortString());
        }
        return entry;
    }

    private Tags collectTags() {
        // Prefer the active context, but fall back to the
        // most-recently-closed one. @AfterReturning advice fires after
        // the target's try-with-resources has already closed it, so
        // current() is typically null by the time we arrive here.
        ProcessContext ctx = ProcessContext.activeOrLast();
        if (ctx == null || tagDescriptors.isEmpty()) return Tags.empty();
        Tags tags = Tags.empty();
        for (TagDescriptor td : tagDescriptors) {
            Object raw = ctx.snapshot().get(td.tagName());
            if (raw == null) continue;
            String value = String.valueOf(raw);
            Set<String> allow = tagAllowLists.getOrDefault(td.tagName(), Collections.emptySet());
            // Empty allow-list means "no cardinality guard for this tag".
            if (!allow.isEmpty() && !allow.contains(value)) {
                value = FALLBACK_TAG_VALUE;
            }
            tags = tags.and(Tag.of(td.tagName(), value));
        }
        return tags;
    }

    private boolean isEnabled(String toggleKey, boolean defaultValue) {
        if (toggleKey == null || toggleKey.isBlank()) return defaultValue;
        return environment.getProperty(toggleKey, Boolean.class, defaultValue);
    }

    private void emitStructuredLog(EventCatalogEntry entry, Tags tags, Throwable ex) {
        ProcessContext ctx = ProcessContext.activeOrLast();
        String correlationId = ctx == null ? "" : ctx.correlationId();
        StringBuilder sb = new StringBuilder(128);
        sb.append("event=").append(entry.eventName());
        sb.append(" metric=").append(entry.metricName());
        sb.append(" correlationId=").append(correlationId);
        for (Tag t : tags) {
            sb.append(' ').append(t.getKey()).append('=').append(t.getValue());
        }
        if (ex == null) {
            log.info(sb.toString());
        } else {
            sb.append(" error=").append(ex.getClass().getSimpleName());
            log.warn(sb.toString());
        }
    }

    // --- accessor used by ServiceCardController ------------------------

    /** Read-only view of the registered event catalog. */
    public Collection<EventCatalogEntry> catalogEntries() {
        return Collections.unmodifiableCollection(catalog.values());
    }

    /** Read-only view of the registered tag descriptors. */
    public List<TagDescriptor> tagDescriptors() {
        return Collections.unmodifiableList(tagDescriptors);
    }
}
