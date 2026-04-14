package com.demo.router;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Parses the package-routing DMN file at startup and exposes a
 * spreadsheet-friendly view of the decision table so
 * {@link ServiceCardController} can render it on the PO-facing page.
 *
 * <p>We only need a handful of fields per cell, so rather than
 * depending on the full Kogito model API, we walk the XML with plain
 * JDK DOM. Keeps the parser trivially portable and cheap to run.
 */
@Component
public class DmnTableParser {

    private static final Logger log = LoggerFactory.getLogger(DmnTableParser.class);
    private static final String RESOURCE = "dmn/package-routing.dmn";

    private String sourceFile = RESOURCE;
    private String hitPolicy = "FIRST";
    private List<String> inputs = List.of();
    private List<String> outputs = List.of();
    private List<Map<String, Object>> rows = List.of();
    private String decisionOutputType = "";
    private List<Map<String, Object>> importedModels = List.of();
    private List<Map<String, Object>> importedTypes = List.of();

    @PostConstruct
    void load() {
        try (InputStream in = new ClassPathResource(RESOURCE).getInputStream()) {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(false);
            DocumentBuilder db = dbf.newDocumentBuilder();
            Document doc = db.parse(in);

            Element table = firstElement(doc.getElementsByTagName("decisionTable"));
            if (table == null) {
                log.warn("dmn.parse.no-decision-table-found");
                return;
            }
            String hp = table.getAttribute("hitPolicy");
            if (hp != null && !hp.isBlank()) this.hitPolicy = hp;

            this.inputs = collectLabels(table.getElementsByTagName("input"), "label");
            this.outputs = collectNames(table.getElementsByTagName("output"));

            List<Map<String, Object>> rowList = new ArrayList<>();
            NodeList ruleNodes = table.getElementsByTagName("rule");
            int idx = 1;
            for (int i = 0; i < ruleNodes.getLength(); i++) {
                Element rule = (Element) ruleNodes.item(i);
                // Filter: ensure the rule is a direct child of the decisionTable.
                if (rule.getParentNode() != table) continue;
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("index", idx++);
                row.put("when", collectEntries(rule.getElementsByTagName("inputEntry"), rule));
                row.put("then", collectEntries(rule.getElementsByTagName("outputEntry"), rule));
                rowList.add(row);
            }
            this.rows = Collections.unmodifiableList(rowList);

            // Capture the decision output typeRef (e.g. "commons.PackageRoute")
            // so the Service Card can display the "Output type" provenance.
            this.decisionOutputType = findDecisionOutputType(doc);

            // Parse <import> elements and resolve each referenced file so
            // /servicecard.json can expose the imported itemDefinitions.
            List<Map<String, Object>> models = new ArrayList<>();
            List<Map<String, Object>> types = new ArrayList<>();
            parseImports(doc, models, types);
            this.importedModels = Collections.unmodifiableList(models);
            this.importedTypes = Collections.unmodifiableList(types);

            log.info("dmn.parse.ok file={} hitPolicy={} inputs={} outputs={} rows={} outputType={} imports={} importedTypes={}",
                    RESOURCE, hitPolicy, inputs.size(), outputs.size(), rowList.size(),
                    decisionOutputType, importedModels.size(), importedTypes.size());
        } catch (IOException | RuntimeException | javax.xml.parsers.ParserConfigurationException
                 | org.xml.sax.SAXException e) {
            log.warn("dmn.parse.failed error={}", e.getMessage());
        }
    }

    public String sourceFile() { return sourceFile; }
    public String hitPolicy() { return hitPolicy; }
    public List<String> inputs() { return inputs; }
    public List<String> outputs() { return outputs; }
    public List<Map<String, Object>> rows() { return rows; }
    public String decisionOutputType() { return decisionOutputType; }
    public List<Map<String, Object>> importedModels() { return importedModels; }
    public List<Map<String, Object>> importedTypes() { return importedTypes; }

    // --- internals ---------------------------------------------------

    private static Element firstElement(NodeList list) {
        for (int i = 0; i < list.getLength(); i++) {
            Node n = list.item(i);
            if (n instanceof Element e) return e;
        }
        return null;
    }

    private static List<String> collectLabels(NodeList list, String attr) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < list.getLength(); i++) {
            Element e = (Element) list.item(i);
            String label = e.getAttribute(attr);
            if (label == null || label.isBlank()) {
                // Fall back to the <inputExpression><text> content.
                NodeList txt = e.getElementsByTagName("text");
                if (txt.getLength() > 0) {
                    label = txt.item(0).getTextContent().trim();
                }
            }
            if (label != null && !label.isBlank()) out.add(label);
        }
        return out;
    }

    private static List<String> collectNames(NodeList list) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < list.getLength(); i++) {
            Element e = (Element) list.item(i);
            String name = e.getAttribute("name");
            if (name == null || name.isBlank()) name = "output" + (i + 1);
            out.add(name);
        }
        return out;
    }

    private static List<String> collectEntries(NodeList list, Element parent) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < list.getLength(); i++) {
            Element e = (Element) list.item(i);
            // Only take direct children of the current <rule>
            if (e.getParentNode() != parent) continue;
            NodeList txt = e.getElementsByTagName("text");
            String text = txt.getLength() > 0 ? txt.item(0).getTextContent().trim() : "";
            out.add(text);
        }
        return out;
    }

    /** Walks <decision><variable typeRef="..."/> and returns the typeRef string. */
    private static String findDecisionOutputType(Document doc) {
        NodeList decisions = doc.getElementsByTagName("decision");
        for (int i = 0; i < decisions.getLength(); i++) {
            Element decision = (Element) decisions.item(i);
            NodeList vars = decision.getElementsByTagName("variable");
            for (int j = 0; j < vars.getLength(); j++) {
                Element var = (Element) vars.item(j);
                if (var.getParentNode() != decision) continue;
                String typeRef = var.getAttribute("typeRef");
                if (typeRef != null && !typeRef.isBlank()) return typeRef;
            }
        }
        return "";
    }

    /**
     * Parses every {@code <import>} in the root DMN and, for each one,
     * best-effort loads the referenced file off the classpath so we can
     * surface its top-level itemDefinitions in /servicecard.json.
     *
     * <p>Resolution rules:
     * <ul>
     *   <li>{@code locationURI} is sibling-relative (bare filename in the
     *       same {@code dmn/} classpath folder) — same convention as the
     *       scesim {@code dmnFilePath}.</li>
     *   <li>The import's {@code name} attribute becomes the FEEL prefix
     *       (e.g. {@code commons}). Imported types are reported as
     *       {@code commons.Document}, {@code commons.PackageRoute}, etc.</li>
     *   <li>Failure to resolve is logged at WARN but does not fail
     *       startup — Kogito's own runtime will fail more loudly in that
     *       case and we do not want duplicate errors.</li>
     * </ul>
     */
    private static void parseImports(Document rootDoc,
                                     List<Map<String, Object>> modelsOut,
                                     List<Map<String, Object>> typesOut) {
        NodeList imports = rootDoc.getElementsByTagName("import");
        for (int i = 0; i < imports.getLength(); i++) {
            Element imp = (Element) imports.item(i);
            String ns = imp.getAttribute("namespace");
            String loc = imp.getAttribute("locationURI");
            String name = imp.getAttribute("name");
            Map<String, Object> model = new LinkedHashMap<>();
            model.put("name", name);
            model.put("namespace", ns);
            model.put("locationURI", loc);
            modelsOut.add(model);

            Optional<Document> imported = loadClasspathDmn("dmn/" + loc);
            if (imported.isEmpty()) {
                log.warn("dmn.parse.import-not-found name={} locationURI={}", name, loc);
                continue;
            }
            Document idoc = imported.get();
            NodeList itemDefs = idoc.getDocumentElement().getChildNodes();
            for (int j = 0; j < itemDefs.getLength(); j++) {
                Node n = itemDefs.item(j);
                if (!(n instanceof Element e)) continue;
                // Namespace-unaware parse: match by tag name (no prefix on
                // the root default-ns elements).
                if (!e.getTagName().equals("itemDefinition")) continue;
                String typeName = e.getAttribute("name");
                if (typeName == null || typeName.isBlank()) continue;
                Map<String, Object> type = new LinkedHashMap<>();
                type.put("prefix", name);
                type.put("name", typeName);
                type.put("qualifiedName",
                        (name == null || name.isBlank()) ? typeName : name + "." + typeName);
                type.put("fields", collectItemComponents(e));
                type.put("sourceFile", loc);
                typesOut.add(type);
            }
        }
    }

    private static List<Map<String, Object>> collectItemComponents(Element itemDefinition) {
        List<Map<String, Object>> fields = new ArrayList<>();
        NodeList kids = itemDefinition.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node n = kids.item(i);
            if (!(n instanceof Element e)) continue;
            if (!e.getTagName().equals("itemComponent")) continue;
            String fieldName = e.getAttribute("name");
            String typeRef = "";
            NodeList trs = e.getElementsByTagName("typeRef");
            if (trs.getLength() > 0) typeRef = trs.item(0).getTextContent().trim();
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("name", fieldName);
            f.put("typeRef", typeRef);
            // Surface allowedValues (e.g. "HIGH","NORMAL","LOW") so POs can
            // see the constraint at a glance on the Service Card.
            NodeList avs = e.getElementsByTagName("allowedValues");
            if (avs.getLength() > 0) {
                Element av = (Element) avs.item(0);
                NodeList tx = av.getElementsByTagName("text");
                if (tx.getLength() > 0) {
                    f.put("allowedValues", tx.item(0).getTextContent().trim());
                }
            }
            fields.add(f);
        }
        return fields;
    }

    private static Optional<Document> loadClasspathDmn(String path) {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(false);
            DocumentBuilder db = dbf.newDocumentBuilder();
            return Optional.of(db.parse(in));
        } catch (IOException | RuntimeException
                 | javax.xml.parsers.ParserConfigurationException
                 | org.xml.sax.SAXException e) {
            log.warn("dmn.parse.import-load-failed path={} error={}", path, e.getMessage());
            return Optional.empty();
        }
    }
}
