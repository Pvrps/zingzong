package io.capture.plugin.reader.lineage;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Proves which scalars are distinctive enough to link two traces, and their normalised form. */
class DistinctiveTest {

    private static final JsonNodeFactory N = JsonNodeFactory.instance;

    @Test
    void strings() {
        assertEquals("Ada Lovelace", Distinctive.normalize(N.textNode("Ada Lovelace"), "args[0].name"));
        assertEquals("ada@example.com", Distinctive.normalize(N.textNode(" ada@example.com "), "x"));
        assertNull(Distinctive.normalize(N.textNode("Amsterdam"), "x"), "single word");
        assertNull(Distinctive.normalize(N.textNode("CONFIRMED"), "x"), "enum-like");
        assertNull(Distinctive.normalize(N.textNode("EUR"), "x"), "too short");
        assertNull(Distinctive.normalize(N.textNode("  "), "x"));
        assertEquals("WIDGET-1", Distinctive.normalize(N.textNode("WIDGET-1"), "x"));
    }

    @Test
    void uuidsAndDates() {
        assertEquals("123e4567-e89b-12d3-a456-426614174000", Distinctive.normalize(N.textNode("123E4567-E89B-12D3-A456-426614174000"), "x"));
        assertEquals("2026-10-30", Distinctive.normalize(N.textNode("2026-10-30"), "x"));
        assertEquals("2026-10-23T20:17:43.430Z", Distinctive.normalize(N.textNode("2026-10-23T20:17:43.430Z"), "x"));
        assertEquals("2028-06", Distinctive.normalize(N.textNode("2028-06"), "x"));
        assertEquals("14:30:15", Distinctive.normalize(N.textNode("14:30:15"), "x"));
    }

    @Test
    void numbers() {
        assertEquals("123.74", Distinctive.normalize(N.numberNode(123.74), "x"));
        assertEquals("123.74", Distinctive.normalize(N.textNode("123.740"), "x"), "numeric strings normalize like numbers");
        assertEquals("65631", Distinctive.normalize(N.numberNode(65631L), "x"));
        assertEquals("1000", Distinctive.normalize(N.numberNode(1000), "x"));
        assertNull(Distinctive.normalize(N.numberNode(999), "args[0].quantity"));
        assertNull(Distinctive.normalize(N.numberNode(3), "x"));
        assertEquals("42", Distinctive.normalize(N.numberNode(42), "result.id"));
        assertEquals("42", Distinctive.normalize(N.numberNode(42), "args[0].orderId"));
        assertEquals("42", Distinctive.normalize(N.textNode("42"), "args[0].customerKey"));
        assertNull(Distinctive.normalize(N.numberNode(42), "args[0].valid"));
        assertNull(Distinctive.normalize(N.numberNode(42), "args[0]"));
        assertNull(Distinctive.normalize(N.numberNode(42), "args[0].width"), "'width' ends in 'th', not 'id'");
    }

    @Test
    void neverBooleansNullsOrContainers() {
        assertNull(Distinctive.normalize(N.booleanNode(true), "x"));
        assertNull(Distinctive.normalize(N.nullNode(), "x"));
        assertNull(Distinctive.normalize(N.objectNode(), "x"));
        assertNull(Distinctive.normalize(N.arrayNode(), "x"));
    }

    @Test
    void identifierPaths() {
        assertTrue(Distinctive.isIdentifierPath("result.id"));
        assertTrue(Distinctive.isIdentifierPath("args[0].orderId"));
        assertTrue(Distinctive.isIdentifierPath("args[0].lines[2].productKey"));
        assertTrue(Distinctive.isIdentifierPath("args[0].poNumber"));
        assertTrue(Distinctive.isIdentifierPath("args[0].order_id"));
        assertTrue(Distinctive.isIdentifierPath("result.ID"));
        assertFalse(Distinctive.isIdentifierPath("args[0].quantity"));
        assertFalse(Distinctive.isIdentifierPath("args[0]"));
        assertFalse(Distinctive.isIdentifierPath("args[0].valid"));
    }
}
