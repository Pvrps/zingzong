package ca.gc.cra.xz02.devops.wltrace.viewer.recording.lineage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;

/**
 * Turns an XML body (a SOAP envelope, say) into a JSON-shaped tree so the flattener can
 * walk it like a JSON body: elements by local name, repeated siblings as arrays, leaf text
 * as the value. Namespaces and attributes are dropped from the paths; this is annotation
 * for lineage, the body itself stays untouched in the trace.
 */
final class XmlBodies {

    private static final JsonNodeFactory N = JsonNodeFactory.instance;

    private XmlBodies() {
    }

    static JsonNode toTree(String text) {
        String t = text.trim();
        if (!t.startsWith("<")) {
            return null;
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setNamespaceAware(true);
            Document doc = factory.newDocumentBuilder().parse(new org.xml.sax.InputSource(new StringReader(t)));
            Element root = doc.getDocumentElement();
            ObjectNode wrapper = N.objectNode();
            wrapper.set(root.getLocalName(), convert(root));
            return wrapper;
        } catch (Exception e) {
            return null;
        }
    }

    private static JsonNode convert(Element element) {
        Map<String, List<JsonNode>> children = new LinkedHashMap<>();
        StringBuilder text = new StringBuilder();
        NodeList nodes = element.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node n = nodes.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE) {
                Element child = (Element) n;
                children.computeIfAbsent(child.getLocalName(), k -> new ArrayList<>()).add(convert(child));
            } else if (n.getNodeType() == Node.TEXT_NODE || n.getNodeType() == Node.CDATA_SECTION_NODE) {
                text.append(n.getNodeValue());
            }
        }
        if (children.isEmpty()) {
            return N.textNode(text.toString().trim());
        }
        ObjectNode node = N.objectNode();
        for (Map.Entry<String, List<JsonNode>> e : children.entrySet()) {
            if (e.getValue().size() == 1) {
                node.set(e.getKey(), e.getValue().get(0));
            } else {
                ArrayNode array = node.putArray(e.getKey());
                for (JsonNode c : e.getValue()) {
                    array.add(c);
                }
            }
        }
        return node;
    }
}
