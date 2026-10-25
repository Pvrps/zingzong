package ca.gc.cra.xz02.devops.wltrace.agent.serialize;

import com.fasterxml.jackson.databind.JsonNode;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.dynamic.loading.ByteArrayClassLoader;
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy;
import org.testng.annotations.Test;

import java.io.Serializable;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.Map;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotSame;
import static org.testng.Assert.assertSame;

/** Proves that per-type state follows the Class object, not the class name: two loaders, same name, different fields, no confusion. */
public class ClassLoaderIsolationTest {

    private static final String NAME = "com.acme.dyn.Thing";

    private final Graphs graphs = new Graphs();

    @Test
    public void sameClassNameInTwoLoadersSerializesWithEachLoadersOwnFields() throws Exception {
        Class<?> a = define(Collections.singletonMap("name", String.class), Collections.singletonMap("version", int.class));
        Class<?> b = define(Collections.singletonMap("name", String.class), Collections.singletonMap("extra", String.class));
        assertEquals(b.getName(), a.getName());
        assertNotSame(b, a);
        assertNotSame(b.getClassLoader(), a.getClassLoader());

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

        assertEquals(nodeA.get("@type").asText(), NAME);
        assertEquals(nodeA.get("version").asInt(), 7);
        assertFalse(nodeA.has("extra"), nodeA.toString());

        assertEquals(nodeB.get("@type").asText(), NAME);
        assertEquals(nodeB.get("extra").asText(), "only-in-B");
        assertFalse(nodeB.has("version"), nodeB.toString());

        assertEquals(nodeA2, nodeA);
    }

    @Test
    public void typeInfoIsKeyedByClassObject() throws Exception {
        Class<?> a = define(Collections.singletonMap("name", String.class), Collections.<String, Class<?>>emptyMap());
        Class<?> b = define(Collections.singletonMap("name", String.class), Collections.singletonMap("extra", String.class));
        TypeInfoCache typeInfos = new TypeInfoCache();
        TypeInfo infoA = typeInfos.of(a);
        TypeInfo infoB = typeInfos.of(b);
        assertNotSame(infoB, infoA);
        assertSame(infoA.type(), a);
        assertSame(infoB.type(), b);
        assertEquals(infoA.fieldCount(), 1);
        assertEquals(infoB.fieldCount(), 2);
        assertSame(typeInfos.of(a), infoA, "stable per Class object");
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
