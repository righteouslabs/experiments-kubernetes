package com.demo.router;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads {@code dmn/package-routing.scesim} and projects each Scenario
 * into a flat {@link Map} the Service Card renders as the "PO-authored
 * test scenarios" table.
 *
 * <p>The .scesim format is Apache KIE Test Scenario 1.8, DMN flavour.
 * Each {@code <Scenario>} carries one {@code <FactMappingValue>} per
 * column; columns are identified by a {@code <expressionIdentifier>
 * <name>} that the KIE 10.1 editor writes as a {@code row|col}
 * coordinate. Columns 3..5 are the three GIVEN inputs, 6..8 are the
 * three EXPECT outputs.
 */
@Component
public class ScesimParser {

    private static final Logger log = LoggerFactory.getLogger(ScesimParser.class);
    private static final String RESOURCE = "dmn/package-routing.scesim";

    /** Parse the shipped scesim and return a {@code {count, scenarios}} block. */
    public Map<String, Object> summary() {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, String>> scenarios = new ArrayList<>();
        try (InputStream in = new ClassPathResource(RESOURCE).getInputStream()) {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(false);
            DocumentBuilder db = dbf.newDocumentBuilder();
            Document doc = db.parse(in);
            NodeList scNodes = doc.getElementsByTagName("Scenario");
            for (int i = 0; i < scNodes.getLength(); i++) {
                Element sc = (Element) scNodes.item(i);
                Map<String, String> row = new LinkedHashMap<>();
                row.put("index", field(sc, "Index"));
                row.put("description", field(sc, "Description"));
                row.put("priority", field(sc, "1|3"));
                row.put("bodyLength", field(sc, "1|4"));
                row.put("enrichmentGrade", field(sc, "1|5"));
                row.put("expectedTopic", field(sc, "1|6"));
                row.put("expectedSlaMinutes", field(sc, "1|7"));
                row.put("expectedReason", field(sc, "1|8"));
                scenarios.add(row);
            }
        } catch (Exception e) {
            log.warn("scesim.parse.failed error={}", e.getMessage());
        }
        out.put("count", scenarios.size());
        out.put("scenarios", scenarios);
        return out;
    }

    /**
     * Find the {@code rawValue} of the first {@code <FactMappingValue>}
     * whose {@code <expressionIdentifier><name>} matches. Returns empty
     * string when the column is missing.
     */
    private static String field(Element scenario, String expressionName) {
        NodeList values = scenario.getElementsByTagName("FactMappingValue");
        for (int i = 0; i < values.getLength(); i++) {
            Element v = (Element) values.item(i);
            NodeList exprs = v.getElementsByTagName("expressionIdentifier");
            if (exprs.getLength() == 0) continue;
            Element expr = (Element) exprs.item(0);
            NodeList names = expr.getElementsByTagName("name");
            if (names.getLength() == 0) continue;
            if (!expressionName.equals(names.item(0).getTextContent().trim())) continue;
            NodeList raws = v.getElementsByTagName("rawValue");
            return raws.getLength() == 0 ? "" : raws.item(0).getTextContent().trim();
        }
        return "";
    }
}
