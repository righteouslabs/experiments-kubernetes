package com.demo.commons.observability;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a bean method as an observable event emission point.
 *
 * <p>The {@link MeterEventAspect} intercepts invocations and, if the
 * configured toggles are enabled, increments a Micrometer counter and
 * optionally emits a structured log line keyed by the named
 * {@link EventCatalogEntry}.
 *
 * <p>The {@code value} is the enum constant name (e.g. {@code
 * "DOCUMENT_ENRICHED"}). The aspect resolves it against a
 * Spring-registered {@code Map<String, EventCatalogEntry>} bean at
 * startup, failing fast if the name is unknown — catching typos at boot
 * rather than at first message.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface MeterEvent {

    /** Event-catalog enum constant name. */
    String value();
}
