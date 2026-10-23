package io.capture.agent.hook;

import io.capture.agent.AgentLog;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.utility.JavaModule;

import java.util.concurrent.atomic.AtomicInteger;

/** Writes transformations and errors to agent.log, never to stdout. */
final class HookListener extends AgentBuilder.Listener.Adapter {

    private final AtomicInteger count = new AtomicInteger();
    private final java.util.Set<String> transformedNames = java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<String, Boolean>());

    @Override
    public void onTransformation(TypeDescription typeDescription, ClassLoader classLoader, JavaModule module,
                                 boolean loaded, DynamicType dynamicType) {
        count.incrementAndGet();
        transformedNames.add(typeDescription.getName());
        AgentLog.info("instrumented " + typeDescription.getName()
                + (loaded ? " (retransformed)" : "")
                + " loader=" + describe(classLoader));
    }

    @Override
    public void onError(String typeName, ClassLoader classLoader, JavaModule module, boolean loaded, Throwable throwable) {
        AgentLog.error("failed to instrument " + typeName + " loader=" + describe(classLoader), throwable);
    }

    /** The named server targets must never be skipped silently: say so when they are. */
    @Override
    public void onIgnored(TypeDescription typeDescription, ClassLoader classLoader, JavaModule module, boolean loaded) {
        if (CaptureHook.isNamedTarget(typeDescription.getName())) {
            AgentLog.warn("hook target ignored by the matcher: " + typeDescription.getName() + " loader=" + describe(classLoader));
        }
    }

    @Override
    public void onDiscovery(String typeName, ClassLoader classLoader, JavaModule module, boolean loaded) {
        if (CaptureHook.isNamedTarget(typeName)) {
            AgentLog.info("hook target discovered: " + typeName + (loaded ? " (loaded)" : " (loading)") + " loader=" + describe(classLoader));
        }
    }

    int count() {
        return count.get();
    }

    void resetCount() {
        count.set(0);
        transformedNames.clear();
    }

    boolean transformed(String className) {
        return transformedNames.contains(className);
    }

    private static String describe(ClassLoader classLoader) {
        if (classLoader == null) {
            return "bootstrap";
        }
        String s = classLoader.toString();
        return s.length() > 120 ? s.substring(0, 120) + "..." : s;
    }
}
