package com.demo.lattice;

import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.function.cloudevent.CloudEventMessageBuilder;
import org.springframework.cloud.function.cloudevent.CloudEventMessageUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;

import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The two HTTP-invoked lattice roles. Which one a deployment serves is
 * selected with SPRING_CLOUD_FUNCTION_DEFINITION (enrich | archive).
 * The third role, order-gen, lives in {@link OrderGenerator}.
 */
@Configuration
public class LatticeFunctions {

    private static final Logger log = LoggerFactory.getLogger(LatticeFunctions.class);

    /** Reads an env var with a fallback for local runs outside Knative. */
    static String env(String name, String fallback) {
        String value = System.getenv(name);
        return (value == null || value.isBlank()) ? fallback : value;
    }

    private final String revision = env("K_REVISION", "lattice-dev");

    /** Adds enrichment fields and replies with a binary-mode CloudEvent. */
    @Bean
    public Function<Message<Map<String, Object>>, Message<Map<String, Object>>> enrich() {
        String mode = env("ENRICH_MODE", "plain");
        return input -> {
            Map<String, Object> order = new LinkedHashMap<>(input.getPayload());
            order.put("enrichedBy", revision);
            order.put("enrichedAt", Instant.now().toString());
            if ("scored".equals(mode)) {
                double amount = order.get("amount") instanceof Number n ? n.doubleValue() : 0.0;
                order.put("priority", amount > 250 ? "HIGH" : "NORMAL");
                order.put("fraudScore", fraudScore(String.valueOf(order.get("orderId"))));
            }
            log.info("ENRICH orderId={} from={} mode={} by={}",
                    order.get("orderId"), CloudEventMessageUtils.getSource(input), mode, revision);
            return CloudEventMessageBuilder.withData(order)
                    .setSpecVersion("1.0")
                    .setId(UUID.randomUUID().toString())
                    .setType("lattice.order.enriched")
                    .setSource(URI.create("/lattice/" + revision))
                    .build();
        };
    }

    /** Persists the order plus selected CloudEvent attributes to MongoDB. */
    @Bean
    public Consumer<Message<Map<String, Object>>> archive() {
        String mongoUri = env("MONGO_URI", "mongodb://mongodb.versioned-demo.svc.cluster.local:27017");
        // MongoClients.create does not connect eagerly, so this is safe even
        // when the deployment only serves the enrich or order-gen role.
        MongoCollection<Document> orders =
                MongoClients.create(mongoUri).getDatabase("latticedb").getCollection("orders");
        return input -> {
            Map<String, Object> order = input.getPayload();
            Document doc = new Document(order);
            doc.put("type", String.valueOf(CloudEventMessageUtils.getType(input)));
            doc.put("source", String.valueOf(CloudEventMessageUtils.getSource(input)));
            doc.put("ceId", String.valueOf(CloudEventMessageUtils.getId(input)));
            orders.insertOne(doc);
            log.info("ARCHIVE orderId={} from={} priority={}",
                    order.get("orderId"),
                    CloudEventMessageUtils.getSource(input),
                    order.getOrDefault("priority", "-"));
        };
    }

    /**
     * spring-cloud-function-web echoes the request's ce-* headers onto the
     * 202 reply of a Consumer, which Knative would mistake for a reply event.
     * Strip them — active only when this deployment serves the archive role.
     */
    @Bean
    @ConditionalOnProperty(name = "spring.cloud.function.definition", havingValue = "archive")
    public Filter stripCloudEventReplyHeaders() {
        return (request, response, chain) -> chain.doFilter(request,
                new HttpServletResponseWrapper((HttpServletResponse) response) {
                    @Override
                    public void setHeader(String name, String value) {
                        if (!name.toLowerCase().startsWith("ce-")) super.setHeader(name, value);
                    }

                    @Override
                    public void addHeader(String name, String value) {
                        if (!name.toLowerCase().startsWith("ce-")) super.addHeader(name, value);
                    }
                });
    }

    /** Deterministic 0.00-1.00 score so replaying an order scores the same. */
    private static double fraudScore(String orderId) {
        return Math.floorMod(orderId.hashCode(), 101) / 100.0;
    }
}
