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
 * Registers document schemas with Confluent Schema Registry under a
 * SINGLE subject ("documents-value") using NONE compatibility mode.
 *
 * This is the key design decision:
 *
 *   - ONE subject, MULTIPLE schema versions (1, 2, 3, ...) — this is
 *     how Schema Registry natively tracks schema evolution.
 *
 *   - Compatibility mode = NONE — we intentionally allow NON-ADDITIVE
 *     changes between versions. This is safe because Dapr's content-based
 *     routing ensures each consumer only receives payloads matching the
 *     schema version(s) it declared support for. A V2 consumer never
 *     sees a V3 document, so breaking changes between V2→V3 are safe.
 *
 *   - Schema Registry serves as the CATALOG and DOCUMENTATION layer,
 *     not as a compatibility gate. The routing layer (Dapr) provides
 *     the safety that would normally come from BACKWARD compatibility.
 *
 * TO ADD A V4:
 *   1. Add SCHEMA_V4 definition to the SCHEMAS map below
 *   2. Add v4 document building in DocumentGenerator
 *   3. Deploy a consumer with SUPPORTED_VERSIONS=4
 *   No routing changes, no Kafka changes, no Dapr config changes.
 */
@Component
public class SchemaRegistrar {

    private static final Logger log = LoggerFactory.getLogger(SchemaRegistrar.class);

    private static final String SUBJECT = "documents-value";

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${app.schema-registry.url:http://schema-registry:8081}")
    private String schemaRegistryUrl;

    // Schema definitions — registered in order under a single subject.
    // Each version can contain NON-ADDITIVE changes because Dapr routing
    // ensures consumers only see versions they support.

    private static final Map<Integer, SchemaDefinition> SCHEMAS = new LinkedHashMap<>();
    static {
        SCHEMAS.put(1, new SchemaDefinition(
            List.of("id", "correlationId", "schemaVersion", "title", "body", "createdAt"),
            """
            {
              "$schema": "http://json-schema.org/draft-07/schema#",
              "title": "Document V1 — Base document",
              "type": "object",
              "required": ["id", "correlationId", "schemaVersion", "title", "body", "createdAt"],
              "properties": {
                "id": { "type": "string" },
                "correlationId": { "type": "string" },
                "schemaVersion": { "type": "integer" },
                "title": { "type": "string" },
                "body": { "type": "string" },
                "createdAt": { "type": "string" }
              }
            }
            """
        ));

        SCHEMAS.put(2, new SchemaDefinition(
            List.of("id", "correlationId", "schemaVersion", "title", "body", "author", "tags", "createdAt"),
            """
            {
              "$schema": "http://json-schema.org/draft-07/schema#",
              "title": "Document V2 — Adds author and tags",
              "type": "object",
              "required": ["id", "correlationId", "schemaVersion", "title", "body", "author", "tags", "createdAt"],
              "properties": {
                "id": { "type": "string" },
                "correlationId": { "type": "string" },
                "schemaVersion": { "type": "integer" },
                "title": { "type": "string" },
                "body": { "type": "string" },
                "author": { "type": "string" },
                "tags": { "type": "array", "items": { "type": "string" } },
                "createdAt": { "type": "string" }
              }
            }
            """
        ));

        SCHEMAS.put(3, new SchemaDefinition(
            List.of("id", "correlationId", "schemaVersion", "title", "body", "author", "tags", "priority", "metadata", "createdAt"),
            """
            {
              "$schema": "http://json-schema.org/draft-07/schema#",
              "title": "Document V3 — Adds priority and structured metadata (non-additive: tags changed to weighted objects)",
              "description": "V3 demonstrates a NON-ADDITIVE change: 'tags' is now an array of {name,weight} objects instead of plain strings. A V2 consumer would break on this. Dapr routing ensures V2 consumers never see V3 documents.",
              "type": "object",
              "required": ["id", "correlationId", "schemaVersion", "title", "body", "author", "tags", "priority", "metadata", "createdAt"],
              "properties": {
                "id": { "type": "string" },
                "correlationId": { "type": "string" },
                "schemaVersion": { "type": "integer" },
                "title": { "type": "string" },
                "body": { "type": "string" },
                "author": { "type": "string" },
                "tags": {
                  "type": "array",
                  "items": {
                    "type": "object",
                    "properties": {
                      "name": { "type": "string" },
                      "weight": { "type": "number" }
                    }
                  }
                },
                "priority": { "type": "string", "enum": ["HIGH", "MEDIUM", "LOW"] },
                "metadata": {
                  "type": "object",
                  "properties": {
                    "source": { "type": "string" },
                    "region": { "type": "string" }
                  }
                },
                "createdAt": { "type": "string" }
              }
            }
            """
        ));
    }

    @PostConstruct
    public void registerSchemas() {
        log.info("Registering schemas under single subject '{}' at {}", SUBJECT, schemaRegistryUrl);

        // Wait for Schema Registry to be reachable before attempting registration.
        // Without this, if the producer starts before schema-registry is ready, the
        // initial POST fails and the producer would never re-register, leaving the
        // registry permanently empty until the next pod restart happens to land on
        // a ready registry.
        if (!waitForSchemaRegistry()) {
            log.error("Schema Registry never became reachable at {}; schemas will NOT be registered. "
                    + "Downstream consumers/dashboard will report 0 subjects.", schemaRegistryUrl);
            return;
        }

        // Set compatibility to NONE so non-additive changes are accepted
        setCompatibility(SUBJECT, "NONE");

        // Register each version in order under the same subject.
        // Schema Registry assigns version numbers 1, 2, 3, ... automatically.
        int registered = 0;
        for (var entry : SCHEMAS.entrySet()) {
            int version = entry.getKey();
            SchemaDefinition def = entry.getValue();
            if (registerSchema(SUBJECT, version, def)) {
                registered++;
            }
        }

        if (registered == SCHEMAS.size()) {
            log.info("All {} schemas registered. View at: {}/subjects/{}/versions",
                    registered, schemaRegistryUrl, SUBJECT);
        } else {
            log.error("Only {}/{} schemas registered. Schema Registry will be incomplete.",
                    registered, SCHEMAS.size());
        }
    }

    /**
     * Polls Schema Registry until its /subjects endpoint returns successfully
     * or the retry budget is exhausted. Uses exponential backoff capped at 10s.
     * Returns true if the registry became reachable, false otherwise.
     */
    private boolean waitForSchemaRegistry() {
        String url = schemaRegistryUrl + "/subjects";
        int maxAttempts = 30; // ~5 minutes of total wait at capped backoff
        long delayMs = 1000L;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                restTemplate.getForEntity(url, String.class);
                log.info("  Schema Registry reachable after {} attempt(s)", attempt);
                return true;
            } catch (Exception e) {
                log.info("  Schema Registry not ready (attempt {}/{}): {} — retrying in {}ms",
                        attempt, maxAttempts, e.getMessage(), delayMs);
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return false;
                }
                delayMs = Math.min(delayMs * 2, 10_000L);
            }
        }
        return false;
    }

    /**
     * Validates a document against the schema for the given version.
     * Checks that all required fields are present and non-null.
     */
    public boolean validate(int version, Map<String, Object> document) {
        SchemaDefinition def = SCHEMAS.get(version);
        if (def == null) {
            log.error("No schema definition for version {}", version);
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

    /** Returns the set of defined schema versions. */
    public Set<Integer> definedVersions() {
        return SCHEMAS.keySet();
    }

    private void setCompatibility(String subject, String level) {
        String url = schemaRegistryUrl + "/config/" + subject;
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.valueOf("application/vnd.schemaregistry.v1+json"));
            String body = "{\"compatibility\":\"" + level + "\"}";
            restTemplate.put(url, new HttpEntity<>(body, headers));
            log.info("  Set compatibility for '{}' to {}", subject, level);
        } catch (Exception e) {
            log.warn("  Could not set compatibility: {}", e.getMessage());
        }
    }

    private boolean registerSchema(String subject, int version, SchemaDefinition def) {
        String url = schemaRegistryUrl + "/subjects/" + subject + "/versions";
        String escaped = def.schemaJson().replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
        String body = "{\"schemaType\":\"JSON\",\"schema\":\"" + escaped + "\"}";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.valueOf("application/vnd.schemaregistry.v1+json"));
        HttpEntity<String> entity = new HttpEntity<>(body, headers);

        // Retry transient errors (connection refused, 5xx, etc.) with backoff.
        int maxAttempts = 5;
        long delayMs = 500L;
        Exception lastError = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                restTemplate.postForEntity(url, entity, String.class);
                log.info("  Registered {}/v{}: {} required fields",
                        subject, version, def.requiredFields().size());
                return true;
            } catch (Exception e) {
                lastError = e;
                log.warn("  Attempt {}/{} to register {}/v{} failed: {}",
                        attempt, maxAttempts, subject, version, e.getMessage());
                if (attempt < maxAttempts) {
                    try {
                        Thread.sleep(delayMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                    delayMs = Math.min(delayMs * 2, 5_000L);
                }
            }
        }
        log.error("  FAILED to register {}/v{} after {} attempts",
                subject, version, maxAttempts, lastError);
        return false;
    }

    private record SchemaDefinition(List<String> requiredFields, String schemaJson) {}
}
