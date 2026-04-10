package com.demo.producer;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Exposes the producer's recently published messages and basic status
 * via HTTP so the dashboard can poll the producer directly and correlate
 * individual publications with consumer processing.
 */
@RestController
public class RecentController {

    private final DocumentGenerator generator;

    public RecentController(DocumentGenerator generator) {
        this.generator = generator;
    }

    @GetMapping("/recent")
    public List<?> recent() {
        return generator.getRecent();
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return Map.of(
                "appName", "producer",
                "publishedCount", generator.getPublishedCount()
        );
    }
}
