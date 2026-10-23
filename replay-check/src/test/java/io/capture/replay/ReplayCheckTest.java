package io.capture.replay;

import io.capture.agent.serialize.JacksonGraphSerializer;
import io.capture.trace.Span;
import io.capture.trace.TraceJson;
import org.junit.jupiter.api.Test;
import javax.naming.Context;
import javax.naming.InitialContext;
import java.lang.reflect.Method;
import java.net.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Hashtable;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** A captured remote EJB call (OrderServiceRemote.createOrder over T3) replays from its span file alone. */
class ReplayCheckTest {

    @Test
    void capturedCreateOrderReplaysOverT3() throws Exception {
        try (Socket probe = new Socket()) {
            probe.connect(new InetSocketAddress("localhost", 7001), 500);
        } catch (java.io.IOException e) {
            assumeTrue(false, "WebLogic is not running on localhost:7001");
        }
        Span span = TraceJson.fromJson(Files.readString(Paths.get("src/test/resources/createOrder-remote.json"))).spans.get(0);
        String oracleHome = System.getenv("ORACLE_HOME");
        URLClassLoader client = new URLClassLoader(new URL[]{
                Paths.get(oracleHome, "wlserver/server/lib/wlthint3client.jar").toUri().toURL(),
                Paths.get(oracleHome, "oracle_common/modules/oracle.toplink/eclipselink.jar").toUri().toURL()},
                ReplayCheckTest.class.getClassLoader());
        Thread.currentThread().setContextClassLoader(client);
        Hashtable<String, String> env = new Hashtable<>();
        env.put(Context.INITIAL_CONTEXT_FACTORY, "weblogic.jndi.WLInitialContextFactory");
        env.put(Context.PROVIDER_URL, "t3://localhost:7001");
        env.put(Context.SECURITY_PRINCIPAL, "weblogic");
        env.put(Context.SECURITY_CREDENTIALS, "welcome1");
        Context ctx = new InitialContext(env);
        Object stub = ctx.lookup(span.ejb.jndiNames.get(0));
        String[] paramNames = span.ejb.signature.replaceAll("^.*\\(|\\)$", "").split(",\\s*");
        Class<?>[] params = new Class<?>[paramNames.length];
        for (int i = 0; i < params.length; i++) {
            params[i] = Class.forName(paramNames[i], true, client);
        }
        Method method = Class.forName(span.ejb.interfaceName, true, client).getMethod(span.ejb.method, params);
        JacksonGraphSerializer reader = new JacksonGraphSerializer();
        Object[] args = new Object[span.args.size()];
        for (int i = 0; i < args.length; i++) {
            args[i] = reader.deserialize(span.args.get(i).value.toString(), method.getGenericParameterTypes()[i], client);
        }
        Object result = method.invoke(stub, args);
        ctx.close();
        Class<?> expected = Class.forName(span.result.declaredType, true, client);
        assertTrue(expected.isInstance(result), "live result is a " + result.getClass().getName());
        assertTrue(expected.isInstance(reader.deserialize(span.result.value.toString(), expected, client)), "captured result rebuilds");
    }
}
