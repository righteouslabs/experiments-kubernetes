package com.demo.consumer.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Handles Dapr pub/sub subscriptions using CONTENT-BASED ROUTING.
 *
 * HOW ROUTING WORKS (the key mechanism):
 *
 * 1. The producer publishes each document as a CloudEvents message with
 *    type = "com.demo.document.v{N}" (e.g. "com.demo.document.v2").
 *    All versions go to the SAME Kafka topic ("documents").
 *
 * 2. This controller's /dapr/subscribe endpoint returns routing rules
 *    with CEL expressions that match on the CloudEvents type attribute.
 *    Rules are auto-generated from the SUPPORTED_VERSIONS env var.
 *
 *    Example for SUPPORTED_VERSIONS=1,2:
 *    {
 *      "routes": {
 *        "rules": [
 *          { "match": "event.type == \"com.demo.document.v1\"", "path": "/documents" },
 *          { "match": "event.type == \"com.demo.document.v2\"", "path": "/documents" }
 *        ],
 *        "default": "/documents/unhandled"
 *      }
 *    }
 *
 * 3. The Dapr sidecar evaluates these rules for every incoming message.
 *    - If the CloudEvents type matches a rule -> message is forwarded to /documents
 *    - If no rule matches -> message goes to /documents/unhandled which returns DROP
 *
 * 4. The DROP response tells Dapr to acknowledge the message without processing.
 *    This means the consumer NEVER SEES messages for unsupported versions.
 *    The filtering happens at the Dapr sidecar level, not in application code.
 *
 * TO ADD A V4:
 *   - Deploy a new consumer with SUPPORTED_VERSIONS=4
 *   - The routing rules auto-generate: match "com.demo.document.v4" -> /documents
 *   - No code changes needed — just configuration
 *
 * MULTI-VERSION CONSUMERS:
 *   - Set SUPPORTED_VERSIONS=1,2,3 to handle all versions in one consumer
 *   - Useful for backward-compatible services during migration
 */
@RestController
public class SubscriptionController {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionController.class);

    private static final String CLOUD_EVENT_TYPE_PREFIX = "com.demo.document.v";

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${app.supported-versions}")
    private String supportedVersionsStr;

    @Value("${app.dapr.pubsub-name}")
    private String pubsubName;

    @Value("${app.dapr.topic}")
    private String topic;

    @Value("${app.dapr.statestore-name}")
    private String statestoreName;

    @Value("${app.dapr.http-port}")
    private int daprPort;

    @Value("${spring.application.name:consumer}")
    private String appName;

    private Set<Integer> supportedVersions;

    // Counters for observability
    private long processedCount = 0;
    private long droppedCount = 0;

    @PostConstruct
    public void init() {
        supportedVersions = Arrays.stream(supportedVersionsStr.split(","))
                .map(String::trim)
                .map(Integer::parseInt)
                .collect(Collectors.toSet());
        log.info("Consumer [{}] initialized — supported schema versions: {}", appName, supportedVersions);
        log.info("  Dapr routing rules will match CloudEvents types: {}",
                supportedVersions.stream()
                        .map(v -> CLOUD_EVENT_TYPE_PREFIX + v)
                        .collect(Collectors.toList()));
    }

    /**
     * Dapr calls this endpoint to discover subscriptions and routing rules.
     *
     * Returns content-based routing rules using CEL expressions that match
     * on the CloudEvents "type" attribute.  Only messages whose type matches
     * a supported version are forwarded to /documents.  Everything else
     * goes to /documents/unhandled and gets DROPped.
     */
    @GetMapping("/dapr/subscribe")
    public List<Map<String, Object>> subscribe() {
        // Build a CEL routing rule for each supported version
        List<Map<String, String>> rules = new ArrayList<>();
        for (int v : supportedVersions) {
            rules.add(Map.of(
                    "match", "event.type == \"" + CLOUD_EVENT_TYPE_PREFIX + v + "\"",
                    "path", "/documents"
            ));
        }

        Map<String, Object> routes = new LinkedHashMap<>();
        routes.put("rules", rules);
        routes.put("default", "/documents/unhandled");

        Map<String, Object> sub = new LinkedHashMap<>();
        sub.put("pubsubname", pubsubName);
        sub.put("topic", topic);
        sub.put("routes", routes);

        return List.of(sub);
    }

    /**
     * Receives documents that MATCHED a routing rule.
     *
     * By the time a message reaches this handler, the Dapr sidecar has already
     * verified that the CloudEvents type matches one of our supported versions.
     * We don't need to filter here — we know the message is for us.
     */
    @PostMapping("/documents")
    @SuppressWarnings("unchecked")
    public ResponseEntity<Map<String, String>> handleDocument(@RequestBody Map<String, Object> cloudEvent) {
        try {
            Object rawData = cloudEvent.getOrDefault("data", cloudEvent);
            Map<String, Object> data;
            if (rawData instanceof Map) {
                data = (Map<String, Object>) rawData;
            } else {
                data = mapper.readValue(rawData.toString(), Map.class);
            }

            Object versionObj = data.get("schemaVersion");
            int schemaVersion = versionObj instanceof Number
                    ? ((Number) versionObj).intValue()
                    : Integer.parseInt(versionObj.toString());

            String id = String.valueOf(data.get("id"));
            String title = String.valueOf(data.get("title"));

            processedCount++;
            log.info("[{}] Processing v{} document: id={} title=\"{}\" [processed={}, dropped={}]",
                    appName, schemaVersion, id, title, processedCount, droppedCount);

            processDocument(schemaVersion, data);
            storeDocument(id, data);

            return ResponseEntity.ok(Map.of("status", "SUCCESS"));

        } catch (Exception e) {
            log.error("[{}] Error handling document: {}", appName, e.getMessage(), e);
            return ResponseEntity.ok(Map.of("status", "RETRY"));
        }
    }

    /**
     * Receives documents that DID NOT match any routing rule.
     *
     * Returning {"status": "DROP"} tells Dapr to acknowledge the message
     * without redelivery.  This is how unsupported versions are silently
     * skipped at the sidecar level — the application code above never sees them.
     */
    @PostMapping("/documents/unhandled")
    public ResponseEntity<Map<String, String>> dropUnhandled(@RequestBody Map<String, Object> cloudEvent) {
        droppedCount++;
        if (droppedCount % 50 == 1) {
            String type = String.valueOf(cloudEvent.getOrDefault("type", "unknown"));
            log.debug("[{}] Dropping unhandled message type={} (total dropped: {})", appName, type, droppedCount);
        }
        return ResponseEntity.ok(Map.of("status", "DROP"));
    }

    /**
     * Version-specific processing logic.
     */
    private void processDocument(int version, Map<String, Object> data) {
        switch (version) {
            case 1 -> {
                log.info("  v1 processing: basic document — title=\"{}\"", data.get("title"));
            }
            case 2 -> {
                log.info("  v2 processing: author={}, tags={}",
                        data.get("author"), data.get("tags"));
            }
            case 3 -> {
                @SuppressWarnings("unchecked")
                Map<String, Object> metadata = (Map<String, Object>) data.getOrDefault("metadata", Map.of());
                log.info("  v3 processing: priority={}, source={}, region={}",
                        data.get("priority"), metadata.get("source"), metadata.get("region"));
            }
            default -> log.warn("  Unknown schema version: {}", version);
        }
    }

    /**
     * Persists a processed document to MongoDB through the Dapr state store API.
     */
    private void storeDocument(String id, Map<String, Object> data) {
        try {
            String stateUrl = String.format("http://localhost:%d/v1.0/state/%s", daprPort, statestoreName);

            Map<String, Object> enriched = new LinkedHashMap<>(data);
            enriched.put("processedBy", appName);
            enriched.put("processedAt", Instant.now().toString());

            List<Map<String, Object>> stateEntries = List.of(Map.of(
                    "key", id,
                    "value", enriched
            ));

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<String> entity = new HttpEntity<>(mapper.writeValueAsString(stateEntries), headers);
            restTemplate.postForEntity(stateUrl, entity, String.class);

            log.debug("  Stored document {} in state store", id);
        } catch (Exception e) {
            log.warn("  Failed to store document {}: {}", id, e.getMessage());
        }
    }

    /**
     * Status endpoint showing consumer configuration and stats.
     */
    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("appName", appName);
        s.put("supportedVersions", supportedVersions);
        s.put("routingRules", supportedVersions.stream()
                .map(v -> "event.type == \"" + CLOUD_EVENT_TYPE_PREFIX + v + "\" -> /documents")
                .collect(Collectors.toList()));
        s.put("processedCount", processedCount);
        s.put("droppedCount", droppedCount);
        return s;
    }
}
