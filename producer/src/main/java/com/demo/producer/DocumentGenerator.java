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
 * and publishes them to a SINGLE Kafka topic via the Dapr pub/sub API.
 *
 * The producer's job is simple:
 *   1. Build a document for a random schema version
 *   2. Validate it against Schema Registry
 *   3. Publish it as plain JSON to Dapr
 *
 * The producer does NOT need to know about consumers or routing.
 * Dapr wraps the payload in CloudEvents and the consumer-side Dapr
 * sidecars inspect event.data.schemaVersion to route to the right handler.
 *
 * V3 demonstrates a NON-ADDITIVE change: "tags" becomes an array of
 * {name, weight} objects instead of plain strings. This would break a
 * V2 consumer, but Dapr routing ensures V2 consumers never see V3 docs.
 *
 * Every document also carries a top-level correlationId so operators can
 * trace an individual message end-to-end from producer publication to
 * consumer processing via the dashboard's Recent Activity view.
 */
@Component
public class DocumentGenerator {

    private static final Logger log = LoggerFactory.getLogger(DocumentGenerator.class);

    private static final int RECENT_BUFFER_SIZE = 20;

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

    private static final String[] TITLES = {
        "Order Created", "User Registered", "Payment Processed",
        "Item Shipped", "Review Submitted", "Inventory Updated",
        "Subscription Renewed", "Alert Triggered"
    };
    private static final String[] AUTHORS = {"alice", "bob", "charlie", "diana", "eve"};
    private static final String[] TAGS_PLAIN = {"urgent", "normal", "batch", "realtime", "analytics", "audit"};
    private static final String[] PRIORITIES = {"HIGH", "MEDIUM", "LOW"};
    private static final String[] SOURCES = {"web", "mobile", "api", "batch"};
    private static final String[] REGIONS = {"us-east-1", "us-west-2", "eu-west-1", "ap-south-1"};

    private long sequenceNumber = 0;

    // Circular buffer of recently published messages — written by the
    // @Scheduled publisher thread, read by HTTP handlers on the /recent
    // endpoint. All access is guarded by `recentLock`.
    private final Object recentLock = new Object();
    private final ArrayDeque<RecentMessage> recent = new ArrayDeque<>(RECENT_BUFFER_SIZE);

    public DocumentGenerator(SchemaRegistrar schemaRegistrar) {
        this.schemaRegistrar = schemaRegistrar;
    }

    @Scheduled(fixedRateString = "${app.producer.interval-ms}")
    public void generate() {
        int version = random.nextInt(3) + 1;
        Map<String, Object> document = buildDocument(version);

        // Validate against the schema registered in Schema Registry
        if (!schemaRegistrar.validate(version, document)) {
            log.error("[seq={}] Document failed schema validation for v{}, dropping", sequenceNumber, version);
            sequenceNumber++;
            return;
        }

        // Publish plain JSON — Dapr wraps it in CloudEvents automatically.
        // The consumer-side Dapr sidecar will inspect event.data.schemaVersion
        // using CEL routing rules to decide where to route the message.
        String daprUrl = String.format("http://localhost:%d/v1.0/publish/%s/%s", daprPort, pubsubName, topic);

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            String payload = mapper.writeValueAsString(document);
            HttpEntity<String> entity = new HttpEntity<>(payload, headers);
            restTemplate.postForEntity(daprUrl, entity, String.class);

            String id = String.valueOf(document.get("id"));
            String correlationId = String.valueOf(document.get("correlationId"));
            String title = String.valueOf(document.get("title"));

            log.info("[seq={}] Published v{} document: id={} correlationId={} title=\"{}\"",
                    sequenceNumber, version, id, correlationId, title);

            RecentMessage msg = new RecentMessage(
                    sequenceNumber,
                    version,
                    id,
                    correlationId,
                    title,
                    Instant.now().toString()
            );
            synchronized (recentLock) {
                if (recent.size() >= RECENT_BUFFER_SIZE) {
                    recent.pollLast();
                }
                recent.addFirst(msg);
            }
        } catch (Exception e) {
            log.warn("[seq={}] Failed to publish: {}", sequenceNumber, e.getMessage());
        }

        sequenceNumber++;
    }

    private Map<String, Object> buildDocument(int version) {
        Map<String, Object> doc = new LinkedHashMap<>();

        // v1 fields (always present) — correlationId is a top-level field on
        // every document, regardless of schema version, to enable tracing.
        doc.put("id", UUID.randomUUID().toString());
        doc.put("correlationId", UUID.randomUUID().toString());
        doc.put("schemaVersion", version);
        doc.put("title", pick(TITLES));
        doc.put("body", "Document body #" + sequenceNumber + " - " + Instant.now());
        doc.put("createdAt", Instant.now().toString());

        // v2 fields — tags are plain strings
        if (version == 2) {
            doc.put("author", pick(AUTHORS));
            doc.put("tags", List.of(pick(TAGS_PLAIN), pick(TAGS_PLAIN)));
        }

        // v3 fields — NON-ADDITIVE CHANGE: tags are now weighted objects,
        // not plain strings. This would break a V2 consumer that expects
        // string[]. Dapr routing prevents V2 consumers from seeing V3 docs.
        if (version == 3) {
            doc.put("author", pick(AUTHORS));
            doc.put("tags", List.of(
                    Map.of("name", pick(TAGS_PLAIN), "weight", Math.round(random.nextDouble() * 100.0) / 100.0),
                    Map.of("name", pick(TAGS_PLAIN), "weight", Math.round(random.nextDouble() * 100.0) / 100.0)
            ));
            doc.put("priority", pick(PRIORITIES));
            Map<String, String> metadata = new LinkedHashMap<>();
            metadata.put("source", pick(SOURCES));
            metadata.put("region", pick(REGIONS));
            doc.put("metadata", metadata);
        }

        return doc;
    }

    private String pick(String[] values) {
        return values[random.nextInt(values.length)];
    }

    /** Returns a snapshot of the recent-publication buffer, newest first. */
    public List<RecentMessage> getRecent() {
        synchronized (recentLock) {
            return new ArrayList<>(recent);
        }
    }

    /** Returns the total number of publish attempts made so far. */
    public long getPublishedCount() {
        return sequenceNumber;
    }

    /** Snapshot of a recently published document for the /recent endpoint. */
    public record RecentMessage(
            long seq,
            int version,
            String id,
            String correlationId,
            String title,
            String publishedAt
    ) {}
}
