package com.demo.commons.observability;

/**
 * A single observable event in a service's catalog.
 *
 * <p>Per-service code declares an enum of events (think "factory-floor
 * stations that get ticketed when a document walks past them") and each
 * constant implements this interface. The {@link MeterEventAspect} uses
 * the interface to look up the counter name and toggle keys without
 * needing any compile-time coupling to the enum class.
 *
 * <p>Manufacturing-theme note: an entry is conceptually a
 * "work-order station" — it knows its own name, what meter to increment,
 * and which feature flags determine whether the station records
 * metrics and logs for the item that just passed through.
 */
public interface EventCatalogEntry {

    /** Business-level name of the event (also used as the enum constant name). */
    String eventName();

    /** Micrometer counter name to increment, e.g. {@code "document.enriched"}. */
    String metricName();

    /** Property key that toggles structured-log emission for this event. */
    String logToggleKey();

    /** Property key that toggles metric emission for this event. */
    String metricToggleKey();

    /**
     * Marks the event as worthy of capture by a downstream audit consumer.
     * Defaults to false — only turn this on for events with
     * business/regulatory significance.
     */
    default boolean auditable() {
        return false;
    }
}
