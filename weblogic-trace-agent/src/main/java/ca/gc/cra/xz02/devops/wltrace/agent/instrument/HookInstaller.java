package ca.gc.cra.xz02.devops.wltrace.agent.instrument;

import ca.gc.cra.xz02.devops.wltrace.agent.bootstrap.AgentLog;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.agent.builder.ResettableClassFileTransformer;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.utility.JavaModule;

import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.Arrays;
import java.util.List;
import java.util.ArrayList;

import static net.bytebuddy.matcher.ElementMatchers.isSynthetic;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.nameStartsWith;
import static net.bytebuddy.matcher.ElementMatchers.not;

/**
 * Installs the hooks' advice while a recording is active and removes it when the recording
 * stops. Installation retransforms targets already loaded; removal restores their original
 * bytecode. With nothing installed the server runs unmodified classes.
 */
public final class HookInstaller {

    /**
     * Never instrumented wherever they are loaded, except for a hook's named targets: the JVM,
     * the agent, the server's own packages and the frameworks it embeds, which an application
     * may also bundle.
     */
    private static final List<String> IGNORED_PACKAGES = Arrays.asList(
            "weblogic.", "oracle.", "com.oracle.", "com.bea.",
            "ca.gc.cra.xz02.devops.wltrace.",
            "java.", "javax.", "jdk.", "sun.", "com.sun.",
            "org.eclipse.persistence.", "org.glassfish.", "org.jboss.weld.");

    private final Instrumentation instrumentation;
    private final List<Hook> hooks;
    private final AgentLog log;
    private final InstrumentationListener listener;
    private ResettableClassFileTransformer transformer;

    public HookInstaller(Instrumentation instrumentation, List<Hook> hooks, AgentLog log) {
        this.instrumentation = instrumentation;
        this.hooks = hooks;
        this.log = log;
        this.listener = new InstrumentationListener(hooks, log);
    }

    public List<Hook> hooks() {
        return hooks;
    }

    public synchronized boolean isInstalled() {
        return transformer != null;
    }

    public synchronized void install() {
        if (transformer != null) {
            return;
        }
        long start = System.nanoTime();
        listener.reset();
        transformer = builder().installOn(instrumentation);
        retransformMissedTargets();
        log.info("hooks installed: " + listener.transformedCount() + " classes instrumented in "
                + (System.nanoTime() - start) / 1000000L + "ms");
    }

    public synchronized void uninstall() {
        if (transformer == null) {
            return;
        }
        long start = System.nanoTime();
        boolean reset = transformer.reset(instrumentation, AgentBuilder.RedefinitionStrategy.RETRANSFORMATION);
        transformer = null;
        log.info("hooks removed (" + (reset ? "bytecode restored" : "nothing to restore") + ") in "
                + (System.nanoTime() - start) / 1000000L + "ms");
    }

    /**
     * Retransforms named targets that the install pass loaded as a side effect: describing a
     * loaded type resolves the types its methods mention, and Byte Buddy's circularity lock
     * skips a class loaded that way.
     */
    private void retransformMissedTargets() {
        List<Class<?>> missed = loadedTargetsNotTransformed();
        if (!missed.isEmpty()) {
            try {
                instrumentation.retransformClasses(missed.toArray(new Class<?>[0]));
                log.info("retransformed " + missed.size() + " hook target(s) loaded during installation: " + missed);
            } catch (Throwable failure) {
                log.error("retransformation of hook targets loaded during installation failed: " + missed, failure);
            }
        }
        List<Class<?>> still = loadedTargetsNotTransformed();
        if (!still.isEmpty()) {
            log.warn("hook targets loaded but not instrumented (no method of the expected shape): " + still);
        }
    }

    private List<Class<?>> loadedTargetsNotTransformed() {
        List<Class<?>> result = new ArrayList<>();
        for (Class<?> loaded : instrumentation.getAllLoadedClasses()) {
            if (listener.isNamedTarget(loaded.getName()) && !listener.transformed(loaded.getName())) {
                result.add(loaded);
            }
        }
        return result;
    }

    private AgentBuilder builder() {
        ElementMatcher.Junction<TypeDescription> ignored = isSynthetic();
        for (String prefix : IGNORED_PACKAGES) {
            ignored = ignored.or(nameStartsWith(prefix));
        }
        for (Hook hook : hooks) {
            for (String target : hook.targetClassNames()) {
                ignored = ignored.and(not(named(target)));
            }
        }
        AgentBuilder builder = new AgentBuilder.Default()
                // No new fields or methods, so loaded classes can be retransformed and restored.
                .disableClassFormatChanges()
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .with(AgentBuilder.InitializationStrategy.NoOp.INSTANCE)
                .with(AgentBuilder.TypeStrategy.Default.REDEFINE)
                .with(listener)
                .ignore(ignored);
        for (Hook hook : hooks) {
            for (AdviceBinding binding : hook.bindings()) {
                builder = builder.type(rawMatcher(binding.types()))
                        .transform(forAdvice().advice(binding.methods(), binding.advice().getName()));
            }
        }
        return builder;
    }

    private static AgentBuilder.RawMatcher rawMatcher(TypeMatcher types) {
        return new AgentBuilder.RawMatcher() {
            @Override
            public boolean matches(TypeDescription type, ClassLoader loader, JavaModule module,
                                   Class<?> classBeingRedefined, ProtectionDomain protectionDomain) {
                return types.matches(type, loader);
            }
        };
    }

    /** The agent jar is on the system class path and on the bootstrap path; the advice class file is located through the former. */
    private static AgentBuilder.Transformer.ForAdvice forAdvice() {
        return new AgentBuilder.Transformer.ForAdvice().include(ClassLoader.getSystemClassLoader());
    }
}
