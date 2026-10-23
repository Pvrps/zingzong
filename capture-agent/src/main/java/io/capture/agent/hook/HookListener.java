package io.capture.agent.hook;

import io.capture.agent.AgentLog;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.utility.JavaModule;

/** Writes transformations and errors to agent.log, never to stdout. */
final class HookListener extends AgentBuilder.Listener.Adapter {

    @Override
    public void onTransformation(TypeDescription typeDescription, ClassLoader classLoader, JavaModule module,
                                 boolean loaded, DynamicType dynamicType) {
        AgentLog.info("instrumented " + typeDescription.getName()
                + (loaded ? " (retransformed)" : "")
                + " loader=" + describe(classLoader));
    }

    @Override
    public void onError(String typeName, ClassLoader classLoader, JavaModule module, boolean loaded, Throwable throwable) {
        AgentLog.error("failed to instrument " + typeName + " loader=" + describe(classLoader), throwable);
    }

    private static String describe(ClassLoader classLoader) {
        if (classLoader == null) {
            return "bootstrap";
        }
        String s = classLoader.toString();
        return s.length() > 120 ? s.substring(0, 120) + "..." : s;
    }
}
