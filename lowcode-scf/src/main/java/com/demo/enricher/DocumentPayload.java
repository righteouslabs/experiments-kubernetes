package com.demo.enricher;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Map;

/**
 * Internal twin of {@link Document} used by
 * {@link CloudEventUnwrappingDeserializer} so Jackson can deserialize
 * the inner payload without recursing back through the custom
 * {@code @JsonDeserialize} on {@link Document}.
 *
 * <p>Same field layout as {@link Document}; carries no business
 * behaviour — {@link #toDocument()} forwards the data across.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DocumentPayload(
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

    public Document toDocument() {
        return new Document(id, schemaVersion, title, body, createdAt,
                correlationId, author, tags, priority, metadata);
    }
}
