package com.demo.commons.observability;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Tiny helper that transparently strips a CloudEvents 1.0 envelope from
 * an inbound JSON tree. Used by service-specific Jackson deserializers
 * so the inner payload flows through unchanged whether or not an
 * upstream component wrapped the message.
 *
 * <pre>
 *   { "specversion": "1.0", "type": "...", "data": { ...payload... } }
 * </pre>
 *
 * <p>Centralising the check here means each service's deserializer is
 * a two-liner and we only have one place to update if the envelope
 * shape ever changes.
 */
public final class CloudEventEnvelope {

    private CloudEventEnvelope() {}

    /**
     * Return {@code node.get("data")} if {@code node} looks like a
     * CloudEvent envelope (has {@code specversion} and an object
     * {@code data} field), otherwise return {@code node} unchanged.
     */
    public static JsonNode unwrap(JsonNode node) {
        if (node != null
                && node.isObject()
                && node.has("specversion")
                && node.has("data")
                && node.get("data").isObject()) {
            return node.get("data");
        }
        return node;
    }
}
