package com.demo.commons.observability;

/**
 * Describes a tag the aspect should attach to counters/logs.
 *
 * <p>Per-service code declares an enum of tag descriptors
 * (e.g. {@code GRADE}, {@code SCHEMA_VERSION}); the aspect reads tag
 * values from the active {@link ProcessContext} using
 * {@link #tagName()} as the attribute key.
 *
 * <p>Using an interface + enum keeps tag names out of magic-string land
 * and lets the Service Card endpoint enumerate the full registered set
 * for documentation purposes.
 */
public interface TagDescriptor {

    /**
     * Canonical tag name — used both as the Micrometer tag key and as
     * the {@link ProcessContext} attribute key the aspect will read.
     */
    String tagName();
}
