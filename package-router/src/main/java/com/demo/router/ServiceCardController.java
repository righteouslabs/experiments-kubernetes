package com.demo.router;

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

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code GET /servicecard.json} — PO-facing introspection endpoint.
 *
 * <p>Same six-section shape as the document-enricher (pipeline, rules,
 * kpis, samples, wiring) plus a {@code dmn} section that surfaces the
 * decision table itself — inputs, outputs, hit policy, the 6 rows, the
 * imported types, and the .scesim scenarios. The dashboard renders
 * this as a live spreadsheet view at
 * {@code /service-card/package-router}.
 */
@RestController
public class ServiceCardController {

    private static final Logger log = LoggerFactory.getLogger(ServiceCardController.class);

    private static final List<String> OUTPUT_TOPICS = List.of(
            "packages.express", "packages.standard", "packages.bulk", "packages.quarantine");

    private final MeterRegistry meterRegistry;
    private final MeterEventAspect aspect;
    private final DmnTableParser dmn;
    private final ScesimParser scesim;
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${service-card.purpose:}")
    private String purpose;

    @Value("${spring.application.name:package-router}")
    private String serviceName;

    public ServiceCardController(MeterRegistry meterRegistry,
                                 MeterEventAspect aspect,
                                 DmnTableParser dmn,
                                 ScesimParser scesim) {
        this.meterRegistry = meterRegistry;
        this.aspect = aspect;
        this.dmn = dmn;
        this.scesim = scesim;
    }

    @GetMapping(value = "/servicecard.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> serviceCard() {
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("name", serviceName);
        card.put("purpose", purpose);
        card.put("pipeline", pipelineSection());
        card.put("rules", ServiceCardSupport.rules(RoutingEngine.class));
        card.put("kpis", ServiceCardSupport.kpis(aspect, meterRegistry, "routing."));
        card.put("samples", samplesSection());
        card.put("wiring", ServiceCardSupport.wiring(
                List.of("kafka://documents.enriched"),
                OUTPUT_TOPICS.stream().map(t -> "kafka://" + t).toList(),
                ServiceCardSupport.DEFAULT_REST_ENDPOINTS));
        card.put("dmn", dmnSection());
        return card;
    }

    // --- dmn ---------------------------------------------------------

    private Map<String, Object> dmnSection() {
        Map<String, Object> table = new LinkedHashMap<>();
        table.put("inputs", dmn.inputs());
        table.put("outputs", dmn.outputs());
        table.put("rows", dmn.rows());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sourceFile", "package-routing.dmn");
        out.put("hitPolicy", dmn.hitPolicy());
        out.put("table", table);
        out.put("decisionOutputType", dmn.decisionOutputType());
        out.put("importedModels", dmn.importedModels());
        out.put("importedTypes", dmn.importedTypes());
        out.put("testScenarios", scesim.summary());
        return out;
    }

    // --- samples -----------------------------------------------------

    private Map<String, Object> samplesSection() {
        Map<String, Object> samples = new LinkedHashMap<>();
        samples.put("input", readJson("samples/enriched-input.json"));
        samples.put("output", readJson("samples/routing-decision.json"));
        return samples;
    }

    private Object readJson(String path) {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return mapper.readValue(in, Object.class);
        } catch (IOException e) {
            log.warn("Sample file {} missing or unreadable: {}", path, e.getMessage());
            return Collections.emptyMap();
        }
    }

    // --- pipeline ----------------------------------------------------

    private Map<String, Object> pipelineSection() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("type", "kafka");
        input.put("topic", "documents.enriched");
        input.put("schemaRef", "documents.enriched#EnrichedDocument");

        List<Map<String, Object>> outputs = new ArrayList<>();
        for (String t : OUTPUT_TOPICS) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("type", "kafka");
            o.put("topic", t);
            outputs.add(o);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("input", input);
        out.put("outputs", outputs);
        out.put("mermaid",
                "flowchart LR\n"
                        + "  in([documents.enriched]) --> router[[DMN: package-routing]]\n"
                        + "  router --> express([packages.express])\n"
                        + "  router --> standard([packages.standard])\n"
                        + "  router --> bulk([packages.bulk])\n"
                        + "  router --> quarantine([packages.quarantine])");
        return out;
    }
}
