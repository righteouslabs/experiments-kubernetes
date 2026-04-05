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
 * Key design points:
 *
 * 1. Each message is published as a CloudEvents envelope with
 *    type = "com.demo.document.v{N}".  Dapr's content-based routing on the
 *    consumer side uses CEL expressions to match this type and deliver the
 *    message ONLY to consumers that declared support for that version.
 *
 * 2. Before publishing, each document is validated against the JSON schema
 *    registered in Schema Registry.  Invalid documents are rejected.
 *
 * 3. All versions flow through a SINGLE Kafka topic ("documents").
 *    No topic-per-version.  Schema Registry tracks which versions are
 *    registered, and the CloudEvents type carries the version discriminator.
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
    private final SchemaRegistrar schemaRegistrar;

    @Value("${app.dapr.http-port}")
    private int daprPort;

    @Value("${app.dapr.pubsub-name}")
    private String pubsubName;

    @Value("${app.dapr.topic}")
    private String topic;

    private static final String CLOUD_EVENT_SOURCE = "document-producer";

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

    public DocumentGenerator(SchemaRegistrar schemaRegistrar) {
        this.schemaRegistrar = schemaRegistrar;
    }

    @Scheduled(fixedRateString = "${app.producer.interval-ms}")
    public void generate() {
        int version = random.nextInt(3) + 1;
        Map<String, Object> document = buildDocument(version);

        // Validate against registered schema before publishing
        if (!schemaRegistrar.validate(version, document)) {
            log.error("[seq={}] Document failed schema validation for v{}, dropping", sequenceNumber, version);
            sequenceNumber++;
            return;
        }

        // Wrap in CloudEvents envelope with version-specific type.
        // This is the key: Dapr consumers use CEL routing rules to match
        // on event.type, so only consumers that support this version
        // will receive the message.
        String cloudEventType = "com.demo.document.v" + version;

        Map<String, Object> cloudEvent = new LinkedHashMap<>();
        cloudEvent.put("specversion", "1.0");
        cloudEvent.put("type", cloudEventType);
        cloudEvent.put("source", CLOUD_EVENT_SOURCE);
        cloudEvent.put("id", UUID.randomUUID().toString());
        cloudEvent.put("datacontenttype", "application/json");
        cloudEvent.put("data", document);

        String daprUrl = String.format("http://localhost:%d/v1.0/publish/%s/%s", daprPort, pubsubName, topic);

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.valueOf("application/cloudevents+json"));

            String payload = mapper.writeValueAsString(cloudEvent);
            HttpEntity<String> entity = new HttpEntity<>(payload, headers);
            restTemplate.postForEntity(daprUrl, entity, String.class);

            log.info("[seq={}] Published v{} document: id={} title=\"{}\" (type={})",
                    sequenceNumber, version, document.get("id"), document.get("title"), cloudEventType);
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
