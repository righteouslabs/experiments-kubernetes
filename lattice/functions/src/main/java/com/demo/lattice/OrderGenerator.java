package com.demo.lattice;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * order-gen role: enabled with ORDER_GEN_ENABLED=true, POSTs a binary-mode
 * lattice.order.created CloudEvent to BROKER_URL every ORDER_INTERVAL_MS.
 */
@Component
@ConditionalOnProperty(name = "order.gen.enabled", havingValue = "true")
public class OrderGenerator {

    private static final Logger log = LoggerFactory.getLogger(OrderGenerator.class);

    private static final String[] ITEMS = {"widget", "gadget", "gizmo", "sprocket", "flange"};
    private static final String[] REGIONS = {"us-east", "eu-west", "apac"};

    private final Random random = new Random();
    private final String revision = LatticeFunctions.env("K_REVISION", "order-gen-dev");
    private final RestTemplate restTemplate;

    @Value("${BROKER_URL:http://kafka-broker-ingress.knative-eventing.svc.cluster.local/lattice-demo/lattice}")
    private String brokerUrl;

    public OrderGenerator() {
        // Bounded timeouts so a slow broker can't wedge the scheduler thread.
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(5000);
        this.restTemplate = new RestTemplate(factory);
    }

    @Scheduled(fixedDelayString = "${ORDER_INTERVAL_MS:5000}")
    public void emit() {
        String orderId = UUID.randomUUID().toString();
        Map<String, Object> order = new LinkedHashMap<>();
        order.put("orderId", orderId);
        order.put("item", ITEMS[random.nextInt(ITEMS.length)]);
        order.put("quantity", random.nextInt(5) + 1);
        order.put("amount", Math.round((10 + random.nextDouble() * 490) * 100.0) / 100.0);
        order.put("region", REGIONS[random.nextInt(REGIONS.length)]);
        order.put("createdAt", Instant.now().toString());
        order.put("producedBy", revision);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("ce-specversion", "1.0");
        headers.set("ce-id", UUID.randomUUID().toString());
        headers.set("ce-type", "lattice.order.created");
        headers.set("ce-source", "/lattice/" + revision);

        log.info("EMIT order.created orderId={} by={}", orderId, revision);
        try {
            restTemplate.postForEntity(brokerUrl, new HttpEntity<>(order, headers), String.class);
        } catch (Exception e) {
            log.warn("EMIT failed orderId={}: {}", orderId, e.getMessage());
        }
    }
}
