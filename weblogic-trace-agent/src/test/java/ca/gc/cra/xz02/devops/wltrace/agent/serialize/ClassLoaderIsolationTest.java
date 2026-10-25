package ca.gc.cra.xz02.devops.wltrace.agent.serialize;

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

/** Proves that per-type state follows the Class object, not the class name: two loaders, same name, different fields, no confusion. */
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

        // A primes the per-type cache, B must not reuse it, A must still be intact afterwards.
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
}
