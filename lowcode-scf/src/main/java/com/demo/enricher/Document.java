package com.demo.enricher;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import java.util.Map;

/**
 * Typed inbound document record — tolerant of v1/v2/v3 schema differences.
 *
 * <p>Uses a Java 17 record so Jackson can deserialize via the canonical
 * constructor without Lombok. Unknown fields are ignored so a v3 payload
 * that carries extra keys does not blow up the v1 reader, and vice versa.
 *
 * <p>The {@code tags} field is declared as {@link Object} because:
 * <ul>
 *   <li>v1 — omitted</li>
 *   <li>v2 — {@code List<String>}</li>
 *   <li>v3 — {@code List<Map<String,Object>>} (name/weight objects)</li>
 * </ul>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonDeserialize(using = CloudEventUnwrappingDeserializer.class)
public record Document(
        String id,
        Integer schemaVersion,
        String title,
        String body,
        String createdAt,
        String correlationId,
        String author,
        Object tags,
        String priority,
        Map<String, Object> metadata
) {

    /** Safe accessor: schemaVersion may be absent on malformed payloads. */
    public int schemaVersionOr(int fallback) {
        return schemaVersion == null ? fallback : schemaVersion;
    }

    /** Safe accessor with a default. */
    public String priorityOrDefault() {
        return priority == null || priority.isBlank() ? "MEDIUM" : priority;
    }

    /** Length of the body text, 0 when null. */
    public int bodyLength() {
        return body == null ? 0 : body.length();
    }

    /** True if tags is a non-empty list (either v2 or v3 shape). */
    public boolean hasTags() {
        return tags instanceof java.util.List<?> list && !list.isEmpty();
    }
}
