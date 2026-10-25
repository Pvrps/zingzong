package ca.gc.cra.xz02.devops.wltrace.viewer.recording.lineage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

/** Proves which scalars are distinctive enough to link two traces, and their normalised form. */
public class DistinctiveValuesTest {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    @DataProvider
    public Object[][] valuesAtPaths() {
        return new Object[][]{
                // strings: six characters or more and not a single word
                {NODES.textNode("Ada Lovelace"), "args[0].name", "Ada Lovelace"},
                {NODES.textNode(" ada@example.com "), "x", "ada@example.com"},
                {NODES.textNode("WIDGET-1"), "x", "WIDGET-1"},
                {NODES.textNode("Amsterdam"), "x", null},
                {NODES.textNode("CONFIRMED"), "x", null},
                {NODES.textNode("EUR"), "x", null},
                {NODES.textNode("  "), "x", null},
                // UUIDs lower-cased; ISO dates, months and times as they are
                {NODES.textNode("123E4567-E89B-12D3-A456-426614174000"), "x", "123e4567-e89b-12d3-a456-426614174000"},
                {NODES.textNode("2026-10-30"), "x", "2026-10-30"},
                {NODES.textNode("2026-10-23T20:17:43.430Z"), "x", "2026-10-23T20:17:43.430Z"},
                {NODES.textNode("2028-06"), "x", "2028-06"},
                {NODES.textNode("14:30:15"), "x", "14:30:15"},
                // numbers: decimals and large integers anywhere, small integers only at identifier paths
                {NODES.numberNode(123.74), "x", "123.74"},
                {NODES.textNode("123.740"), "x", "123.74"},
                {NODES.numberNode(65631L), "x", "65631"},
                {NODES.numberNode(1000), "x", "1000"},
                {NODES.numberNode(999), "args[0].quantity", null},
                {NODES.numberNode(3), "x", null},
                {NODES.numberNode(42), "result.id", "42"},
                {NODES.numberNode(42), "args[0].orderId", "42"},
                {NODES.textNode("42"), "args[0].customerKey", "42"},
                {NODES.numberNode(42), "args[0].valid", null},
                {NODES.numberNode(42), "args[0]", null},
                {NODES.numberNode(42), "args[0].width", null},
                // never booleans, nulls or containers
                {NODES.booleanNode(true), "x", null},
                {NODES.nullNode(), "x", null},
                {NODES.objectNode(), "x", null},
                {NODES.arrayNode(), "x", null},
        };
    }

    @Test(dataProvider = "valuesAtPaths")
    public void normalisesDistinctiveValuesAndRejectsTheRest(JsonNode value, String path, String expected) {
        assertEquals(DistinctiveValues.normalize(value, path), expected);
    }

    @Test
    public void identifierPathsEndInAnIdentifierWord() {
        assertTrue(DistinctiveValues.isIdentifierPath("result.id"));
        assertTrue(DistinctiveValues.isIdentifierPath("args[0].orderId"));
        assertTrue(DistinctiveValues.isIdentifierPath("args[0].lines[2].productKey"));
        assertTrue(DistinctiveValues.isIdentifierPath("args[0].poNumber"));
        assertTrue(DistinctiveValues.isIdentifierPath("args[0].order_id"));
        assertTrue(DistinctiveValues.isIdentifierPath("result.ID"));
        assertFalse(DistinctiveValues.isIdentifierPath("args[0].quantity"));
        assertFalse(DistinctiveValues.isIdentifierPath("args[0]"));
        assertFalse(DistinctiveValues.isIdentifierPath("args[0].valid"));
    }
}
