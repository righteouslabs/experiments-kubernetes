package com.demo.router;

import com.demo.commons.observability.CloudEventEnvelope;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;

/**
 * Strips the CloudEvents 1.0 envelope (if any) from inbound JSON so
 * Spring Cloud Stream's message converter can deserialize the plain
 * {@link EnrichedDocument} payload. Registered as a Jackson module via
 * {@link Wiring}.
 */
public class CloudEventUnwrappingDeserializer extends StdDeserializer<EnrichedDocument> {

    private static final ObjectMapper INNER = JsonMapper.builder().build();

    public CloudEventUnwrappingDeserializer() {
        super(EnrichedDocument.class);
    }

    @Override
    public EnrichedDocument deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        JsonNode target = CloudEventEnvelope.unwrap(p.getCodec().readTree(p));
        return INNER.treeToValue(target, EnrichedDocument.class);
    }

    @Configuration
    public static class Wiring {
        @Bean
        public SimpleModule cloudEventUnwrappingModule() {
            SimpleModule m = new SimpleModule("cloud-event-unwrap");
            m.addDeserializer(EnrichedDocument.class, new CloudEventUnwrappingDeserializer());
            return m;
        }
    }
}
