package com.demo.commons.observability;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares one or more lightweight "tick" counters to increment
 * whenever the annotated method returns successfully.
 *
 * <p>Useful for sub-step milestones that do not deserve a full
 * {@link EventCatalogEntry} but are still worth counting — e.g. a
 * per-stage heartbeat or a cache-hit marker. The aspect creates
 * Micrometer counters named exactly as given in {@link #names()}.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Tick {

    /** One or more counter names to tick on successful return. */
    String[] names();
}
