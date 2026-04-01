package com.demo.producer;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.*;

/**
 * Generates documents with randomly selected schema versions (v1, v2, v3)
 * and publishes them to Kafka via the Dapr pub/sub API.
 *
 * Schema evolution:
 *   v1 = base fields (id, title, body, createdAt)
 *   v2 = v1 + author, tags
 *   v3 = v2 + priority, metadata (source, region, correlationId)
 */
@Component
public class DocumentGenerator {

    private static final Logger log = LoggerFactory.getLogger(DocumentGenerator.class);

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper mapper = new ObjectMapper();
    private final Random random = new Random();

    @Value("${app.dapr.http-port}")
    private int daprPort;

    @Value("${app.dapr.pubsub-name}")
    private String pubsubName;

    @Value("${app.dapr.topic}")
    private String topic;

    private static final String[] TITLES = {
        "Order Created", "User Registered", "Payment Processed",
        "Item Shipped", "Review Submitted", "Inventory Updated",
        "Subscription Renewed", "Alert Triggered"
    };
    private static final String[] AUTHORS = {"alice", "bob", "charlie", "diana", "eve"};
    private static final String[] TAGS = {"urgent", "normal", "batch", "realtime", "analytics", "audit"};
    private static final String[] PRIORITIES = {"HIGH", "MEDIUM", "LOW"};
    private static final String[] SOURCES = {"web", "mobile", "api", "batch"};
    private static final String[] REGIONS = {"us-east-1", "us-west-2", "eu-west-1", "ap-south-1"};

    private long sequenceNumber = 0;

    @Scheduled(fixedRateString = "${app.producer.interval-ms}")
    public void generate() {
        int version = random.nextInt(3) + 1;
        Map<String, Object> document = buildDocument(version);

        String daprUrl = String.format("http://localhost:%d/v1.0/publish/%s/%s", daprPort, pubsubName, topic);

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("metadata.rawPayload", "true");

            String payload = mapper.writeValueAsString(document);
            HttpEntity<String> entity = new HttpEntity<>(payload, headers);
            restTemplate.postForEntity(daprUrl, entity, String.class);

            log.info("[seq={}] Published v{} document: id={} title=\"{}\"",
                    sequenceNumber, version, document.get("id"), document.get("title"));
        } catch (Exception e) {
            log.warn("[seq={}] Failed to publish: {}", sequenceNumber, e.getMessage());
        }

        sequenceNumber++;
    }

    private Map<String, Object> buildDocument(int version) {
        Map<String, Object> doc = new LinkedHashMap<>();

        // v1 fields (always present)
        doc.put("id", UUID.randomUUID().toString());
        doc.put("schemaVersion", version);
        doc.put("title", pick(TITLES));
        doc.put("body", "Document body #" + sequenceNumber + " - " + Instant.now());
        doc.put("createdAt", Instant.now().toString());

        // v2 fields
        if (version >= 2) {
            doc.put("author", pick(AUTHORS));
            doc.put("tags", List.of(pick(TAGS), pick(TAGS)));
        }

        // v3 fields
        if (version >= 3) {
            doc.put("priority", pick(PRIORITIES));
            Map<String, String> metadata = new LinkedHashMap<>();
            metadata.put("source", pick(SOURCES));
            metadata.put("region", pick(REGIONS));
            metadata.put("correlationId", UUID.randomUUID().toString());
            doc.put("metadata", metadata);
        }

        return doc;
    }

    private String pick(String[] values) {
        return values[random.nextInt(values.length)];
    }
}
