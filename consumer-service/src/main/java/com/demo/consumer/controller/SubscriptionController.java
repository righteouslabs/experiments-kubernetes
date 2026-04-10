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
 * Dapr content-based pub/sub routing — the consumer's core mechanism.
 *
 * ┌──────────────────────────────────────────────────────────────────┐
 * │                    HOW ROUTING WORKS                             │
 * │                                                                  │
 * │  1. Producer publishes plain JSON to a SINGLE Kafka topic.       │
 * │     The document contains a "schemaVersion" field (1, 2, or 3).  │
 * │     Dapr automatically wraps it in a CloudEvents envelope.       │
 * │                                                                  │
 * │  2. This consumer's /dapr/subscribe endpoint returns CEL         │
 * │     routing rules that inspect the ACTUAL PAYLOAD:               │
 * │                                                                  │
 * │       event.data.schemaVersion == 2  →  /documents               │
 * │       (default)                      →  /documents/unhandled     │
 * │                                                                  │
 * │  3. The Dapr sidecar evaluates these rules for EVERY message.    │
 * │     It parses the JSON payload, reads event.data.schemaVersion,  │
 * │     and either:                                                  │
 * │       - Forwards matching messages to /documents (processed)     │
 * │       - Forwards non-matching to /documents/unhandled (DROPped)  │
 * │                                                                  │
 * │  4. The application NEVER SEES messages for versions it doesn't  │
 * │     support. This is what makes non-additive schema changes      │
 * │     safe — V2 consumers never receive V3 documents that have     │
 * │     incompatible field structures.                               │
 * │                                                                  │
 * │  KEY INSIGHT: Routing is based on data CONTENT inspection by     │
 * │  the Dapr sidecar, not just envelope metadata. Dapr reads the    │
 * │  actual schemaVersion field from the parsed JSON payload.        │
 * └──────────────────────────────────────────────────────────────────┘
 *
 * TO ADD A V4:
 *   Deploy a consumer with SUPPORTED_VERSIONS=4. The CEL rule
 *   "event.data.schemaVersion == 4" is auto-generated. Done.
 *
 * MULTI-VERSION CONSUMERS:
 *   Set SUPPORTED_VERSIONS=1,2 to handle V1 and V2 in one service.
 *   Two CEL rules are generated, one per version. The service logic
 *   uses a switch to process each version differently.
 */
@RestController
public class SubscriptionController {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionController.class);

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

    private long processedCount = 0;
    private long droppedCount = 0;

    // Circular buffer of recently processed messages — written by the
    // Dapr HTTP handler thread(s), read by HTTP handlers on the /recent
    // endpoint. All access is guarded by `recentLock`.
    private static final int RECENT_BUFFER_SIZE = 20;
    private final Object recentLock = new Object();
    private final ArrayDeque<RecentProcessed> recent = new ArrayDeque<>(RECENT_BUFFER_SIZE);

    /** Snapshot of a recently processed document for the /recent endpoint. */
    public record RecentProcessed(
            String id,
            int version,
            String correlationId,
            String title,
            String processedAt
    ) {}

    @PostConstruct
    public void init() {
        supportedVersions = Arrays.stream(supportedVersionsStr.split(","))
                .map(String::trim)
                .map(Integer::parseInt)
                .collect(Collectors.toSet());

        log.info("Consumer [{}] — supported schema versions: {}", appName, supportedVersions);
        log.info("  Dapr CEL routing rules (evaluated on PAYLOAD content):");
        for (int v : supportedVersions) {
            log.info("    event.data.schemaVersion == {} → /documents", v);
        }
        log.info("    (default) → /documents/unhandled → DROP");
    }

    /**
     * Dapr subscription endpoint — returns content-based routing rules.
     *
     * The CEL expressions inspect event.data.schemaVersion (the actual
     * payload field) to route messages. This is NOT metadata/header
     * matching — Dapr parses the JSON and reads the field value.
     */
    @GetMapping("/dapr/subscribe")
    public List<Map<String, Object>> subscribe() {
        List<Map<String, String>> rules = new ArrayList<>();
        for (int v : supportedVersions) {
            rules.add(Map.of(
                    "match", "event.data.schemaVersion == " + v,
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
     * Handles messages that MATCHED a routing rule.
     *
     * By the time a message reaches here, the Dapr sidecar has already
     * verified that event.data.schemaVersion matches one of our supported
     * versions. The service logic is lean — it only handles versions it
     * was designed for. No defensive filtering needed.
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
            String correlationId = data.get("correlationId") == null
                    ? ""
                    : String.valueOf(data.get("correlationId"));

            processedCount++;
            log.info("[{}] Processing v{} document: id={} correlationId={} title=\"{}\" [processed={}, dropped={}]",
                    appName, schemaVersion, id, correlationId, title, processedCount, droppedCount);

            // Version-specific business logic — each version can have
            // fundamentally different processing, including non-additive changes
            processDocument(schemaVersion, data);
            storeDocument(id, data);

            // Record in the recent-activity buffer so the dashboard can
            // correlate this processed message with the producer's publication.
            RecentProcessed processed = new RecentProcessed(
                    id,
                    schemaVersion,
                    correlationId,
                    title,
                    Instant.now().toString()
            );
            synchronized (recentLock) {
                if (recent.size() >= RECENT_BUFFER_SIZE) {
                    recent.pollLast();
                }
                recent.addFirst(processed);
            }

            return ResponseEntity.ok(Map.of("status", "SUCCESS"));

        } catch (Exception e) {
            log.error("[{}] Error handling document: {}", appName, e.getMessage(), e);
            return ResponseEntity.ok(Map.of("status", "RETRY"));
        }
    }

    /**
     * Handles messages that DID NOT match any routing rule.
     *
     * Returning DROP tells Dapr to acknowledge the message without redelivery.
     * The application code in /documents never sees these messages.
     */
    @PostMapping("/documents/unhandled")
    public ResponseEntity<Map<String, String>> dropUnhandled(@RequestBody Map<String, Object> cloudEvent) {
        droppedCount++;
        return ResponseEntity.ok(Map.of("status", "DROP"));
    }

    /**
     * Version-specific processing — each version has different business logic.
     *
     * Note how V3 processes tags as weighted objects {name, weight} while V2
     * processes them as plain strings. This is a NON-ADDITIVE change that
     * would break if V2 received a V3 document. Dapr routing prevents this.
     */
    @SuppressWarnings("unchecked")
    private void processDocument(int version, Map<String, Object> data) {
        Object correlationId = data.get("correlationId");
        switch (version) {
            case 1 -> {
                log.info("  [v1] basic document — correlationId={} title=\"{}\"",
                        correlationId, data.get("title"));
            }
            case 2 -> {
                // V2 tags are plain strings: ["urgent", "batch"]
                List<String> tags = (List<String>) data.get("tags");
                log.info("  [v2] correlationId={} author={} tags={} (plain strings)",
                        correlationId, data.get("author"), tags);
            }
            case 3 -> {
                // V3 tags are weighted objects: [{name:"urgent", weight:0.8}]
                // This is a NON-ADDITIVE change from V2's string array.
                List<Map<String, Object>> tags = (List<Map<String, Object>>) data.get("tags");
                log.info("  [v3] correlationId={} priority={} tags={} (weighted objects)",
                        correlationId, data.get("priority"),
                        tags.stream()
                                .map(t -> t.get("name") + ":" + t.get("weight"))
                                .collect(Collectors.joining(", ")));
                Map<String, Object> metadata = (Map<String, Object>) data.getOrDefault("metadata", Map.of());
                log.info("  [v3] source={}, region={}",
                        metadata.get("source"), metadata.get("region"));
            }
            default -> log.warn("  Unknown schema version: {}", version);
        }
    }

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
        } catch (Exception e) {
            log.warn("  Failed to store document {}: {}", id, e.getMessage());
        }
    }

    /**
     * Returns the most recent messages processed by this consumer,
     * newest first, so the dashboard can correlate them with the
     * producer's recent publications.
     */
    @GetMapping("/recent")
    public List<RecentProcessed> recent() {
        synchronized (recentLock) {
            return new ArrayList<>(recent);
        }
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("appName", appName);
        s.put("supportedVersions", supportedVersions);
        s.put("routingRules", supportedVersions.stream()
                .map(v -> "event.data.schemaVersion == " + v + " → /documents")
                .collect(Collectors.toList()));
        s.put("defaultRoute", "/documents/unhandled → DROP");
        s.put("processedCount", processedCount);
        s.put("droppedCount", droppedCount);
        return s;
    }
}
