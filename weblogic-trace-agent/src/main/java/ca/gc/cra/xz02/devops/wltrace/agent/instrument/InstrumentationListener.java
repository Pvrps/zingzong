package ca.gc.cra.xz02.devops.wltrace.agent.instrument;

import ca.gc.cra.xz02.devops.wltrace.agent.bootstrap.AgentLog;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.utility.JavaModule;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Writes every transformation, error and ignored named target to agent.log and remembers which named targets were transformed. */
final class InstrumentationListener extends AgentBuilder.Listener.Adapter {

    private final List<Hook> hooks;
    private final AgentLog log;
    private final AtomicInteger transformedCount = new AtomicInteger();
    private final Set<String> transformedNames = Collections.newSetFromMap(new ConcurrentHashMap<>());

    InstrumentationListener(List<Hook> hooks, AgentLog log) {
        this.hooks = hooks;
        this.log = log;
    }

    @Override
    public void onTransformation(TypeDescription type, ClassLoader loader, JavaModule module, boolean loaded, DynamicType dynamicType) {
        transformedCount.incrementAndGet();
        transformedNames.add(type.getName());
        log.info("instrumented " + type.getName() + (loaded ? " (retransformed)" : "") + " loader=" + describe(loader));
    }

    @Override
    public void onError(String typeName, ClassLoader loader, JavaModule module, boolean loaded, Throwable failure) {
        log.error("failed to instrument " + typeName + " loader=" + describe(loader), failure);
    }

    @Override
    public void onIgnored(TypeDescription type, ClassLoader loader, JavaModule module, boolean loaded) {
        if (isNamedTarget(type.getName())) {
            log.warn("hook target ignored by the matcher: " + type.getName() + " loader=" + describe(loader));
        }
    }

    @Override
    public void onDiscovery(String typeName, ClassLoader loader, JavaModule module, boolean loaded) {
        if (isNamedTarget(typeName)) {
            log.info("hook target discovered: " + typeName + (loaded ? " (loaded)" : " (loading)") + " loader=" + describe(loader));
        }
    }

    int transformedCount() {
        return transformedCount.get();
    }

    void reset() {
        transformedCount.set(0);
        transformedNames.clear();
    }

    boolean transformed(String className) {
        return transformedNames.contains(className);
    }

    boolean isNamedTarget(String className) {
        return hooks.stream().anyMatch(hook -> hook.targets(className));
    }

    static String describe(ClassLoader loader) {
        if (loader == null) {
            return "bootstrap";
        }
        String text = loader.toString();
        return text.length() > 120 ? text.substring(0, 120) + "..." : text;
    }
}
