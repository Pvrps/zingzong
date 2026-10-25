package ca.gc.cra.xz02.devops.wltrace.agent.serialize;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

/** Proves the JSON shape of every marker kind, the one place the format's {@code @}-keys are written. */
public class MarkerTest {

    @DataProvider
    public Object[][] markersAndTheirJson() {
        return new Object[][]{
                {Marker.type("com.example.Thing"), "{\"@type\":\"com.example.Thing\"}"},
                {Marker.id(3), "{\"@id\":3}"},
                {Marker.ref(3), "{\"@ref\":3}"},
                {Marker.notSerializable(), "{\"@serializable\":false}"},
                {Marker.lazy(), "{\"@lazy\":true,\"loaded\":false}"},
                {Marker.truncated("elements", 7), "{\"@truncated\":7,\"what\":\"elements\"}"},
                {Marker.text("abcde", 10, 5), "{\"@text\":\"abcde\",\"length\":10,\"@truncated\":5,\"what\":\"chars\"}"},
                {Marker.text("short", 5, 0), "{\"@text\":\"short\",\"length\":5}"},
                {Marker.bytes("AQI=", 2, 0), "{\"@bytes\":\"AQI=\",\"length\":2}"},
                {Marker.bytes("AQIDBA==", 6, 2), "{\"@bytes\":\"AQIDBA==\",\"length\":6,\"@truncated\":2,\"what\":\"bytes\"}"},
                {Marker.jms("TextMessage"), "{\"@jms\":\"TextMessage\"}"},
                {Marker.unserializable(null, "boom", null), "{\"@unserializable\":\"null\",\"reason\":\"boom\"}"},
        };
    }

    @Test(dataProvider = "markersAndTheirJson")
    public void eachKindHasOneJsonShape(Marker marker, String expectedJson) {
        assertEquals(marker.toNode().toString(), expectedJson);
    }

    @Test
    public void handleAndUnserializableNameTheObjectByIdentity() {
        Object value = new StringBuilder("x");
        ObjectNode handle = Marker.handle(value, "stream", "x").toNode();
        assertEquals(handle.get("@handle").asText(), "java.lang.StringBuilder");
        assertEquals(handle.get("kind").asText(), "stream");
        assertTrue(handle.get("identity").asText().startsWith("java.lang.StringBuilder@"));
        assertEquals(handle.get("toString").asText(), "x");

        ObjectNode unserializable = Marker.unserializable(value, "failed", null).toNode();
        assertEquals(unserializable.get("@unserializable").asText(), "java.lang.StringBuilder");
        assertEquals(unserializable.get("reason").asText(), "failed");
        assertTrue(unserializable.has("identity"));
        assertFalse(unserializable.has("toString"));
    }

    @Test
    public void skippedAppendsToTheOwnersList() {
        ObjectNode owner = JsonNodeFactory.instance.objectNode();
        Marker.type("com.example.Thing").writeTo(owner);
        Marker.skipped("this$0", "compiler-injected").writeTo(owner);
        Marker.skipped("_persistence_fetchGroup", "weaver-injected").writeTo(owner);
        assertEquals(owner.get("@skipped").size(), 2);
        assertEquals(owner.get("@skipped").get(1).get("field").asText(), "_persistence_fetchGroup");
        assertEquals(owner.get("@skipped").get(1).get("reason").asText(), "weaver-injected");
        assertEquals(owner.get("@type").asText(), "com.example.Thing");
    }

    @Test
    public void writeToAddsTheMarkerAndItsDetailsToAnExistingObject() {
        ObjectNode map = JsonNodeFactory.instance.objectNode();
        map.put("k0", 0);
        Marker.truncated("entries", 4).writeTo(map);
        assertEquals(map.get("@truncated").asInt(), 4);
        assertEquals(map.get("what").asText(), "entries");
        assertEquals(map.get("k0").asInt(), 0);
    }
}
