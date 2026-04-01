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
 * Handles Dapr pub/sub subscriptions and incoming documents.
 *
 * On startup, reads SUPPORTED_VERSIONS (e.g. "1" or "1,2" or "1,2,3") and
 * only processes documents whose schemaVersion is in that set.  Documents
 * with unsupported versions are acknowledged but skipped — they will be
 * handled by a different consumer instance that does support that version.
 *
 * Processed documents are persisted to MongoDB via the Dapr state store API.
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

    // Counters for observability
    private long processedCount = 0;
    private long skippedCount = 0;

    @PostConstruct
    public void init() {
        supportedVersions = Arrays.stream(supportedVersionsStr.split(","))
                .map(String::trim)
                .map(Integer::parseInt)
                .collect(Collectors.toSet());
        log.info("Consumer [{}] initialized — supported schema versions: {}", appName, supportedVersions);
    }

    /**
     * Dapr calls this endpoint to discover which topics this app subscribes to.
     */
    @GetMapping("/dapr/subscribe")
    public List<Map<String, Object>> subscribe() {
        Map<String, Object> sub = new LinkedHashMap<>();
        sub.put("pubsubname", pubsubName);
        sub.put("topic", topic);
        sub.put("route", "/documents");
        return List.of(sub);
    }

    /**
     * Receives documents from Dapr pub/sub (CloudEvents envelope).
     * Processes only those matching our supported schema versions.
     */
    @PostMapping("/documents")
    @SuppressWarnings("unchecked")
    public ResponseEntity<Map<String, String>> handleDocument(@RequestBody Map<String, Object> cloudEvent) {
        try {
            // Dapr wraps the payload in a CloudEvents envelope; the actual data is under "data"
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

            if (!supportedVersions.contains(schemaVersion)) {
                skippedCount++;
                log.debug("[{}] Skipping v{} document {} (not in supported set {})",
                        appName, schemaVersion, id, supportedVersions);
                return ResponseEntity.ok(Map.of("status", "SUCCESS"));
            }

            processedCount++;
            log.info("[{}] Processing v{} document: id={} title=\"{}\" [processed={}, skipped={}]",
                    appName, schemaVersion, id, title, processedCount, skippedCount);

            // Version-specific processing
            processDocument(schemaVersion, data);

            // Persist to MongoDB via Dapr state store
            storeDocument(id, data);

            return ResponseEntity.ok(Map.of("status", "SUCCESS"));

        } catch (Exception e) {
            log.error("[{}] Error handling document: {}", appName, e.getMessage(), e);
            return ResponseEntity.ok(Map.of("status", "RETRY"));
        }
    }

    /**
     * Version-specific processing logic.
     * Each schema version may trigger different business logic.
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

            // Add processing metadata
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
     * Simple health/status endpoint showing consumer configuration and stats.
     */
    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("appName", appName);
        s.put("supportedVersions", supportedVersions);
        s.put("processedCount", processedCount);
        s.put("skippedCount", skippedCount);
        return s;
    }
}
