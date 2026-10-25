package ca.gc.cra.xz02.devops.wltrace.viewer.recording.lineage;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Proves which scalars are distinctive enough to link two traces, and their normalised form. */
class DistinctiveValuesTest {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    @Test
    void strings() {
        assertEquals("Ada Lovelace", DistinctiveValues.normalize(NODES.textNode("Ada Lovelace"), "args[0].name"));
        assertEquals("ada@example.com", DistinctiveValues.normalize(NODES.textNode(" ada@example.com "), "x"));
        assertNull(DistinctiveValues.normalize(NODES.textNode("Amsterdam"), "x"), "single word");
        assertNull(DistinctiveValues.normalize(NODES.textNode("CONFIRMED"), "x"), "enum-like");
        assertNull(DistinctiveValues.normalize(NODES.textNode("EUR"), "x"), "too short");
        assertNull(DistinctiveValues.normalize(NODES.textNode("  "), "x"));
        assertEquals("WIDGET-1", DistinctiveValues.normalize(NODES.textNode("WIDGET-1"), "x"));
    }

    @Test
    void uuidsAndDates() {
        assertEquals("123e4567-e89b-12d3-a456-426614174000", DistinctiveValues.normalize(NODES.textNode("123E4567-E89B-12D3-A456-426614174000"), "x"));
        assertEquals("2026-10-30", DistinctiveValues.normalize(NODES.textNode("2026-10-30"), "x"));
        assertEquals("2026-10-23T20:17:43.430Z", DistinctiveValues.normalize(NODES.textNode("2026-10-23T20:17:43.430Z"), "x"));
        assertEquals("2028-06", DistinctiveValues.normalize(NODES.textNode("2028-06"), "x"));
        assertEquals("14:30:15", DistinctiveValues.normalize(NODES.textNode("14:30:15"), "x"));
    }

    @Test
    void numbers() {
        assertEquals("123.74", DistinctiveValues.normalize(NODES.numberNode(123.74), "x"));
        assertEquals("123.74", DistinctiveValues.normalize(NODES.textNode("123.740"), "x"), "numeric strings normalize like numbers");
        assertEquals("65631", DistinctiveValues.normalize(NODES.numberNode(65631L), "x"));
        assertEquals("1000", DistinctiveValues.normalize(NODES.numberNode(1000), "x"));
        assertNull(DistinctiveValues.normalize(NODES.numberNode(999), "args[0].quantity"));
        assertNull(DistinctiveValues.normalize(NODES.numberNode(3), "x"));
        assertEquals("42", DistinctiveValues.normalize(NODES.numberNode(42), "result.id"));
        assertEquals("42", DistinctiveValues.normalize(NODES.numberNode(42), "args[0].orderId"));
        assertEquals("42", DistinctiveValues.normalize(NODES.textNode("42"), "args[0].customerKey"));
        assertNull(DistinctiveValues.normalize(NODES.numberNode(42), "args[0].valid"));
        assertNull(DistinctiveValues.normalize(NODES.numberNode(42), "args[0]"));
        assertNull(DistinctiveValues.normalize(NODES.numberNode(42), "args[0].width"), "'width' ends in 'th', not 'id'");
    }

    @Test
    void neverBooleansNullsOrContainers() {
        assertNull(DistinctiveValues.normalize(NODES.booleanNode(true), "x"));
        assertNull(DistinctiveValues.normalize(NODES.nullNode(), "x"));
        assertNull(DistinctiveValues.normalize(NODES.objectNode(), "x"));
        assertNull(DistinctiveValues.normalize(NODES.arrayNode(), "x"));
    }

    @Test
    void identifierPaths() {
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
