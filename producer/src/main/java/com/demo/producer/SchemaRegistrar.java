package com.demo.producer;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.*;

/**
 * Registers JSON schemas with Confluent Schema Registry on startup and
 * validates outgoing documents against those schemas before publishing.
 *
 * All document versions share a SINGLE Kafka topic ("documents").
 * Schema Registry tracks each version as a separate subject
 * ("documents-v1", "documents-v2", etc.) so you get:
 *   - Central schema catalog for discovery
 *   - Compatibility checking when evolving schemas
 *   - Runtime validation at the producer (pre-publish gate)
 *
 * The schemas define which fields are required for each version.
 * Validation here is structural (required-field checks) matching
 * the JSON Schema definitions in schemas/.
 *
 * To add a V4:
 *   1. Define SCHEMA_V4 with required fields
 *   2. Add to SCHEMAS map
 *   3. Add v4 document building in DocumentGenerator
 *   4. Deploy a consumer with SUPPORTED_VERSIONS=4
 *   That's it — no routing changes needed.
 */
@Component
public class SchemaRegistrar {

    private static final Logger log = LoggerFactory.getLogger(SchemaRegistrar.class);

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${app.schema-registry.url:http://schema-registry:8081}")
    private String schemaRegistryUrl;

    // Schema definitions — these match the JSON Schema files in schemas/
    // Each version defines its required fields for validation.

    private static final Map<Integer, SchemaDefinition> SCHEMAS = Map.of(
        1, new SchemaDefinition(
            "documents-v1",
            List.of("id", "schemaVersion", "title", "body", "createdAt"),
            """
            {
              "$schema": "http://json-schema.org/draft-07/schema#",
              "title": "Document V1",
              "type": "object",
              "required": ["id", "schemaVersion", "title", "body", "createdAt"],
              "properties": {
                "id": { "type": "string" },
                "schemaVersion": { "type": "integer", "const": 1 },
                "title": { "type": "string" },
                "body": { "type": "string" },
                "createdAt": { "type": "string" }
              }
            }
            """
        ),
        2, new SchemaDefinition(
            "documents-v2",
            List.of("id", "schemaVersion", "title", "body", "author", "tags", "createdAt"),
            """
            {
              "$schema": "http://json-schema.org/draft-07/schema#",
              "title": "Document V2",
              "type": "object",
              "required": ["id", "schemaVersion", "title", "body", "author", "tags", "createdAt"],
              "properties": {
                "id": { "type": "string" },
                "schemaVersion": { "type": "integer", "const": 2 },
                "title": { "type": "string" },
                "body": { "type": "string" },
                "author": { "type": "string" },
                "tags": { "type": "array", "items": { "type": "string" } },
                "createdAt": { "type": "string" }
              }
            }
            """
        ),
        3, new SchemaDefinition(
            "documents-v3",
            List.of("id", "schemaVersion", "title", "body", "author", "tags", "priority", "metadata", "createdAt"),
            """
            {
              "$schema": "http://json-schema.org/draft-07/schema#",
              "title": "Document V3",
              "type": "object",
              "required": ["id", "schemaVersion", "title", "body", "author", "tags", "priority", "metadata", "createdAt"],
              "properties": {
                "id": { "type": "string" },
                "schemaVersion": { "type": "integer", "const": 3 },
                "title": { "type": "string" },
                "body": { "type": "string" },
                "author": { "type": "string" },
                "tags": { "type": "array", "items": { "type": "string" } },
                "priority": { "type": "string", "enum": ["HIGH", "MEDIUM", "LOW"] },
                "metadata": { "type": "object" },
                "createdAt": { "type": "string" }
              }
            }
            """
        )
    );

    @PostConstruct
    public void registerSchemas() {
        log.info("Registering {} document schemas with Schema Registry at {}", SCHEMAS.size(), schemaRegistryUrl);

        for (var entry : SCHEMAS.entrySet()) {
            int version = entry.getKey();
            SchemaDefinition def = entry.getValue();

            // Register under per-version subject (used by this demo for routing)
            registerSchema(def.subject(), version, def);

            // Also register under the standard TopicNameStrategy subject
            // (documents-value) so Schema Registry tracks the evolution lineage.
            // In production with Avro, this single subject + BACKWARD compat
            // is the canonical Confluent pattern.
            registerSchema("documents-value", version, def);
        }
    }

    /**
     * Validates a document against the schema for the given version.
     * Checks that all required fields are present and non-null.
     */
    public boolean validate(int version, Map<String, Object> document) {
        SchemaDefinition def = SCHEMAS.get(version);
        if (def == null) {
            log.error("No schema registered for version {}", version);
            return false;
        }

        for (String field : def.requiredFields()) {
            if (!document.containsKey(field) || document.get(field) == null) {
                log.error("Validation failed for v{}: missing required field '{}'", version, field);
                return false;
            }
        }

        return true;
    }

    /** Returns the set of registered schema versions. */
    public Set<Integer> registeredVersions() {
        return SCHEMAS.keySet();
    }

    private void registerSchema(String subject, int version, SchemaDefinition def) {
        String url = schemaRegistryUrl + "/subjects/" + subject + "/versions";

        try {
            // Wrap the schema JSON for Schema Registry's expected format
            String escaped = def.schemaJson().replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
            String body = "{\"schemaType\":\"JSON\",\"schema\":\"" + escaped + "\"}";

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.valueOf("application/vnd.schemaregistry.v1+json"));

            HttpEntity<String> entity = new HttpEntity<>(body, headers);
            restTemplate.postForEntity(url, entity, String.class);

            log.info("  Registered schema: subject={} (v{}, {} required fields)",
                    subject, version, def.requiredFields().size());
        } catch (Exception e) {
            log.warn("  Failed to register {}/v{}: {} (Schema Registry may not be ready yet)",
                    subject, version, e.getMessage());
        }
    }

    private record SchemaDefinition(String subject, List<String> requiredFields, String schemaJson) {}
}
