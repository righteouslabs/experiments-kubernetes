package com.demo.enricher;

import com.demo.commons.observability.MeterEventAspect;
import com.demo.commons.observability.ServiceCardSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * {@code GET /servicecard.json} — the PO-facing surface for the
 * document-enricher.
 *
 * <p>Aggregates four views at runtime:
 * <ol>
 *   <li><strong>Pipeline</strong> — the Kafka in/out topics, drawn from
 *       the bundled AsyncAPI spec;</li>
 *   <li><strong>Decision rules</strong> — reflected off the
 *       {@code @BusinessRule} annotations on
 *       {@link EnrichmentEngine#apply};</li>
 *   <li><strong>KPIs</strong> — the declared event catalog plus any
 *       {@code enrichment.*} meters the registry has observed;</li>
 *   <li><strong>Samples</strong> — canonical input/output JSON shipped
 *       on the classpath.</li>
 * </ol>
 */
@RestController
public class ServiceCardController {

    private static final Logger log = LoggerFactory.getLogger(ServiceCardController.class);

    private final MeterRegistry meterRegistry;
    private final MeterEventAspect aspect;
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${service-card.purpose:}")
    private String purpose;

    @Value("${spring.application.name:document-enricher}")
    private String serviceName;

    public ServiceCardController(MeterRegistry meterRegistry, MeterEventAspect aspect) {
        this.meterRegistry = meterRegistry;
        this.aspect = aspect;
    }

    @GetMapping(value = "/servicecard.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> serviceCard() {
        Map<String, Object> spec = loadAsyncApi();
        String inTopic = topicFromSpec(spec, "documents", "documents");
        String outTopic = topicFromSpec(spec, "documentsEnriched", "documents.enriched");

        Map<String, Object> card = new LinkedHashMap<>();
        card.put("name", serviceName);
        card.put("purpose", purpose);
        card.put("pipeline", pipelineSection(inTopic, outTopic));
        card.put("rules", ServiceCardSupport.rules(EnrichmentEngine.class));
        card.put("kpis", ServiceCardSupport.kpis(aspect, meterRegistry, "enrichment."));
        card.put("samples", samplesSection());
        card.put("wiring", ServiceCardSupport.wiring(
                List.of("kafka://" + inTopic),
                List.of("kafka://" + outTopic),
                ServiceCardSupport.DEFAULT_REST_ENDPOINTS));
        return card;
    }

    // --- pipeline -----------------------------------------------------

    private Map<String, Object> pipelineSection(String inTopic, String outTopic) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("type", "kafka");
        input.put("topic", inTopic);
        input.put("schemaRef", "asyncapi.yaml#/components/schemas/Document");

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("type", "kafka");
        output.put("topic", outTopic);
        output.put("schemaRef", "asyncapi.yaml#/components/schemas/EnrichedDocument");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("input", input);
        out.put("output", output);
        out.put("mermaid",
                "flowchart LR\n"
                        + "  in([" + inTopic + "]) --> enrich[[enrich&#40;&#41;]]\n"
                        + "  enrich --> out([" + outTopic + "])");
        return out;
    }

    // --- samples ------------------------------------------------------

    private Map<String, Object> samplesSection() {
        Map<String, Object> samples = new LinkedHashMap<>();
        samples.put("input", readJson("samples/document-input.json"));
        samples.put("output", readJson("samples/document-output.json"));
        return samples;
    }

    private Object readJson(String path) {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return mapper.readValue(in, Object.class);
        } catch (IOException e) {
            log.warn("Sample file {} missing or unreadable: {}", path, e.getMessage());
            return Map.of();
        }
    }

    // --- AsyncAPI spec access ----------------------------------------

    @SuppressWarnings("unchecked")
    private Map<String, Object> loadAsyncApi() {
        try (InputStream in = new ClassPathResource("asyncapi.yaml").getInputStream()) {
            Object parsed = new Yaml().load(in);
            if (parsed instanceof Map<?, ?> m) return (Map<String, Object>) m;
        } catch (IOException e) {
            log.warn("asyncapi.yaml not found on classpath: {}", e.getMessage());
        } catch (Exception e) {
            log.warn("Failed to parse asyncapi.yaml: {}", e.getMessage());
        }
        return new TreeMap<>();
    }

    @SuppressWarnings("unchecked")
    private static String topicFromSpec(Map<String, Object> spec, String channel, String fallback) {
        Object channels = spec.get("channels");
        if (channels instanceof Map<?, ?> cmap && cmap.get(channel) instanceof Map<?, ?> ch
                && ch.get("address") instanceof String addr && !addr.isBlank()) {
            return addr;
        }
        return fallback;
    }
}
