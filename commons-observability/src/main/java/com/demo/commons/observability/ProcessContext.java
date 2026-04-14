package com.demo.commons.observability;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A per-invocation observability context. Use as a try-with-resources
 * value so the thread-local slot is always cleared — even on
 * exceptional return — without callers needing a {@code finally} block.
 *
 * <pre>{@code
 *   try (var ctx = ProcessContext.open(correlationId)) {
 *       ctx.put(EnrichmentTags.GRADE.tagName(), "A");
 *       // ... business work ...
 *   }
 * }</pre>
 *
 * <p>The class stores a small bag of named attributes which the
 * {@link MeterEventAspect} reads when attaching Micrometer tags and
 * structured-log fields. Attributes are <strong>typed-on-read</strong>:
 * {@link #get(String, Class)} enforces the caller's expected type.
 *
 * <p>Guarantees:
 * <ul>
 *   <li>Re-entrancy is rejected: {@link #open(String)} throws
 *       {@link IllegalStateException} if a context is already open on the
 *       current thread. This is a safety fence against forgotten closes.</li>
 *   <li>{@link #close()} removes the thread-local — repeated closes are
 *       harmless.</li>
 * </ul>
 */
public final class ProcessContext implements AutoCloseable {

    private static final ThreadLocal<ProcessContext> CURRENT = new ThreadLocal<>();

    /**
     * The most-recently-closed context on this thread. The aspect reads
     * from this slot during {@code @AfterReturning} advice — by that
     * point the user's try-with-resources has already closed the
     * context, so {@link #current()} returns {@code null} and a
     * "last-seen" snapshot is required to surface tags to Micrometer.
     */
    private static final ThreadLocal<ProcessContext> LAST_CLOSED = new ThreadLocal<>();

    private final Map<String, Object> attributes = new ConcurrentHashMap<>();
    private final String correlationId;
    private volatile boolean closed = false;

    private ProcessContext(String correlationId) {
        this.correlationId = correlationId == null ? "" : correlationId;
    }

    /**
     * Open a new context for the current thread. Must be closed exactly
     * once — prefer try-with-resources.
     *
     * @throws IllegalStateException if a context is already open on the
     *         current thread (likely a missing close somewhere upstream)
     */
    public static ProcessContext open(String correlationId) {
        if (CURRENT.get() != null) {
            throw new IllegalStateException(
                    "ProcessContext already open on this thread; nested open() is not supported");
        }
        ProcessContext ctx = new ProcessContext(correlationId);
        CURRENT.set(ctx);
        return ctx;
    }

    /** Current context, or {@code null} when none is open on this thread. */
    public static ProcessContext current() {
        return CURRENT.get();
    }

    /**
     * Most-recently-closed context on this thread. Intended for the
     * observability aspect, whose {@code @AfterReturning} advice fires
     * after the target method's try-with-resources has already closed
     * {@link #current()}. Returns {@code null} if no context has
     * closed on this thread yet.
     */
    public static ProcessContext lastClosed() {
        return LAST_CLOSED.get();
    }

    /**
     * Resolve the active or most-recently-closed context on this
     * thread — used by the aspect to surface tag values regardless of
     * whether the target method already closed the try-with-resources.
     */
    public static ProcessContext activeOrLast() {
        ProcessContext c = CURRENT.get();
        return c != null ? c : LAST_CLOSED.get();
    }

    /** Correlation id the context was opened with (never null). */
    public String correlationId() {
        return correlationId;
    }

    /** Attach an attribute visible to the aspect and to downstream code. */
    public void put(String key, Object value) {
        if (key == null || value == null) return;
        attributes.put(key, value);
    }

    /** Typed read — returns {@code null} if absent or wrong type. */
    public <T> T get(String key, Class<T> type) {
        Object v = attributes.get(key);
        return type.isInstance(v) ? type.cast(v) : null;
    }

    /** Unmodifiable snapshot of the current attribute bag. */
    public Map<String, Object> snapshot() {
        return Collections.unmodifiableMap(attributes);
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        // Publish the final attribute snapshot to LAST_CLOSED so the
        // observability aspect can still read tags during its
        // @AfterReturning advice. CURRENT is cleared so re-entry works.
        LAST_CLOSED.set(this);
        CURRENT.remove();
    }
}
