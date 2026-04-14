package com.demo.enricher;

import com.demo.commons.observability.CloudEventEnvelope;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.io.IOException;

/**
 * Strips the CloudEvents 1.0 envelope Dapr wraps around each pub/sub
 * message so the business {@code Function} sees a plain {@link Document}.
 * Delegates the shape-check to {@link CloudEventEnvelope#unwrap}.
 */
public class CloudEventUnwrappingDeserializer extends StdDeserializer<Document> {

    /** Private mapper avoids recursing back through the {@code @JsonDeserialize} on Document. */
    private static final ObjectMapper INNER = JsonMapper.builder().build();

    public CloudEventUnwrappingDeserializer() {
        super(Document.class);
    }

    @Override
    public Document deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        JsonNode target = CloudEventEnvelope.unwrap(p.getCodec().readTree(p));
        return INNER.treeToValue(target, DocumentPayload.class).toDocument();
    }
}
