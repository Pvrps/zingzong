package io.capture.agent.hook;

import io.capture.agent.AgentLog;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.utility.JavaModule;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Writes transformations, errors and ignored named targets to agent.log, never to stdout. */
final class HookListener extends AgentBuilder.Listener.Adapter {

    private final AtomicInteger count = new AtomicInteger();
    private final Set<String> transformedNames = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    private final Set<String> keptOut = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

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

    @Override
    public void onIgnored(TypeDescription typeDescription, ClassLoader classLoader, JavaModule module, boolean loaded) {
        if (ContainerTargets.isNamed(typeDescription.getName())) {
            AgentLog.warn("hook target ignored by the matcher: " + typeDescription.getName() + " loader=" + describe(classLoader));
        }
    }

    @Override
    public void onDiscovery(String typeName, ClassLoader classLoader, JavaModule module, boolean loaded) {
        if (ContainerTargets.isNamed(typeName)) {
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

    /** An annotated class the ownership rule refused, logged once per class. */
    void keptOut(String layer, String className, ClassLoader loader) {
        if (keptOut.add(className)) {
            AgentLog.info("not instrumented (" + layer + " annotation, but not an application class): " + className
                    + " loader=" + describe(loader));
        }
    }

    private static String describe(ClassLoader classLoader) {
        if (classLoader == null) {
            return "bootstrap";
        }
        String s = classLoader.toString();
        return s.length() > 120 ? s.substring(0, 120) + "..." : s;
    }
}
