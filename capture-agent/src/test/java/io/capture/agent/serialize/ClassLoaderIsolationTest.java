package io.capture.agent.serialize;

import com.fasterxml.jackson.databind.JsonNode;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.dynamic.loading.ByteArrayClassLoader;
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy;
import org.junit.jupiter.api.Test;

import java.io.Serializable;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WebLogic loads each application class in more than one loader (analysis, then runtime)
 * and again on every redeploy. Per-type state must therefore follow the Class object, not
 * the class name: two loaders, same name, different shapes, no confusion in either direction.
 */
class ClassLoaderIsolationTest {

    private static final String NAME = "com.acme.dyn.Thing";

    private final Graphs graphs = new Graphs();

    @Test
    void sameClassNameInTwoLoadersSerializesWithEachLoadersOwnFields() throws Exception {
        Class<?> a = define(Collections.singletonMap("name", String.class), Collections.singletonMap("version", int.class));
        Class<?> b = define(Collections.singletonMap("name", String.class), Collections.singletonMap("extra", String.class));
        assertEquals(a.getName(), b.getName());
        assertNotSame(a, b);
        assertNotSame(a.getClassLoader(), b.getClassLoader());

        Object instanceA = a.getDeclaredConstructor().newInstance();
        set(instanceA, "name", "from-A");
        set(instanceA, "version", 7);
        Object instanceB = b.getDeclaredConstructor().newInstance();
        set(instanceB, "name", "from-B");
        set(instanceB, "extra", "only-in-B");

        // Prime the per-type cache with A, then serialize B, then A again.
        JsonNode nodeA = graphs.write(instanceA).node;
        JsonNode nodeB = graphs.write(instanceB).node;
        JsonNode nodeA2 = graphs.write(instanceA).node;

        assertEquals(NAME, nodeA.get("@type").asText());
        assertEquals(7, nodeA.get("version").asInt());
        assertFalse(nodeA.has("extra"), nodeA.toString());

        assertEquals(NAME, nodeB.get("@type").asText());
        assertEquals("only-in-B", nodeB.get("extra").asText());
        assertFalse(nodeB.has("version"), nodeB.toString());

        assertEquals(nodeA, nodeA2);
    }

    @Test
    void deserializationResolvesTypeThroughTheGivenLoaderOnly() throws Exception {
        Class<?> a = define(Collections.singletonMap("name", String.class), Collections.singletonMap("version", int.class));
        Class<?> b = define(Collections.singletonMap("name", String.class), Collections.singletonMap("extra", String.class));

        Object instanceA = a.getDeclaredConstructor().newInstance();
        set(instanceA, "name", "from-A");
        set(instanceA, "version", 7);
        JsonNode nodeA = graphs.write(instanceA).node;

        Object backInA = graphs.read(nodeA, Object.class, a.getClassLoader());
        assertSame(a, backInA.getClass());
        assertEquals(7, get(backInA, "version"));

        // The same JSON read through loader B yields B's class: "version" is unknown there and skipped.
        Object backInB = graphs.read(nodeA, Object.class, b.getClassLoader());
        assertSame(b, backInB.getClass());
        assertEquals("from-A", get(backInB, "name"));
        assertTrue(hasField(b, "extra") && !hasField(b, "version"));
    }

    @Test
    void typeInfoIsKeyedByClassObject() throws Exception {
        Class<?> a = define(Collections.singletonMap("name", String.class), Collections.<String, Class<?>>emptyMap());
        Class<?> b = define(Collections.singletonMap("name", String.class), Collections.singletonMap("extra", String.class));
        TypeInfo infoA = TypeInfo.of(a);
        TypeInfo infoB = TypeInfo.of(b);
        assertNotSame(infoA, infoB);
        assertSame(a, infoA.type);
        assertSame(b, infoB.type);
        assertEquals(1, infoA.fields.size());
        assertEquals(2, infoB.fields.size());
        assertSame(infoA, TypeInfo.of(a), "stable per Class object");
    }

    private static Class<?> define(Map<String, Class<?>> fields1, Map<String, Class<?>> fields2) {
        ByteBuddy byteBuddy = new ByteBuddy();
        net.bytebuddy.dynamic.DynamicType.Builder<?> builder = byteBuddy.subclass(Object.class)
                .name(NAME)
                .implement(Serializable.class);
        for (Map.Entry<String, Class<?>> e : fields1.entrySet()) {
            builder = builder.defineField(e.getKey(), e.getValue(), Modifier.PUBLIC);
        }
        for (Map.Entry<String, Class<?>> e : fields2.entrySet()) {
            builder = builder.defineField(e.getKey(), e.getValue(), Modifier.PUBLIC);
        }
        ClassLoader loader = new ByteArrayClassLoader(ClassLoaderIsolationTest.class.getClassLoader(),
                Collections.<String, byte[]>emptyMap());
        return builder.make().load(loader, ClassLoadingStrategy.Default.INJECTION).getLoaded();
    }

    private static void set(Object target, String field, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static Object get(Object target, String field) throws Exception {
        Field f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        return f.get(target);
    }

    private static boolean hasField(Class<?> type, String name) {
        try {
            type.getDeclaredField(name);
            return true;
        } catch (NoSuchFieldException e) {
            return false;
        }
    }
}
