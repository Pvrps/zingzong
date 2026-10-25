package ca.gc.cra.xz02.devops.wltrace.viewer.recording.lineage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns an XML body, such as a SOAP envelope, into a JSON-shaped tree so the flattener can walk it
 * like a JSON body: elements by local name, repeated siblings as arrays, leaf text as the value.
 * Namespaces and attributes are left out of the paths; the body itself stays untouched in the trace.
 */
final class XmlBodies {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private XmlBodies() {
    }

    /** The tree, or null when the text is not XML. */
    static JsonNode toTree(String text) {
        String trimmed = text.trim();
        if (!trimmed.startsWith("<")) {
            return null;
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setNamespaceAware(true);
            Document document = factory.newDocumentBuilder().parse(new InputSource(new StringReader(trimmed)));
            Element root = document.getDocumentElement();
            ObjectNode wrapper = NODES.objectNode();
            wrapper.set(root.getLocalName(), convert(root));
            return wrapper;
        } catch (Exception notXml) {
            return null;
        }
    }

    private static JsonNode convert(Element element) {
        Map<String, List<JsonNode>> children = new LinkedHashMap<>();
        StringBuilder text = new StringBuilder();
        NodeList nodes = element.getChildNodes();
        for (int index = 0; index < nodes.getLength(); index++) {
            Node node = nodes.item(index);
            if (node.getNodeType() == Node.ELEMENT_NODE) {
                Element child = (Element) node;
                children.computeIfAbsent(child.getLocalName(), name -> new ArrayList<>()).add(convert(child));
            } else if (node.getNodeType() == Node.TEXT_NODE || node.getNodeType() == Node.CDATA_SECTION_NODE) {
                text.append(node.getNodeValue());
            }
        }
        if (children.isEmpty()) {
            return NODES.textNode(text.toString().trim());
        }
        ObjectNode converted = NODES.objectNode();
        for (Map.Entry<String, List<JsonNode>> entry : children.entrySet()) {
            if (entry.getValue().size() == 1) {
                converted.set(entry.getKey(), entry.getValue().get(0));
            } else {
                ArrayNode array = converted.putArray(entry.getKey());
                entry.getValue().forEach(array::add);
            }
        }
        return converted;
    }
}
