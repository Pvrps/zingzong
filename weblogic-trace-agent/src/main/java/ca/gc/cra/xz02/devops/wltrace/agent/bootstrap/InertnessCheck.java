package ca.gc.cra.xz02.devops.wltrace.agent.bootstrap;

import ca.gc.cra.xz02.devops.wltrace.agent.instrument.Hook;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Decides whether this JVM is a WebLogic server the agent can work in. It is one when the
 * {@code weblogic.home} property or the {@code weblogic.Server} class is present and every
 * hook finds its target classes on the system class path. Anywhere else the agent
 * stays inert: it installs nothing and the control MBean reports the reason.
 */
public final class InertnessCheck {

    private InertnessCheck() {
    }

    /** The reason the agent must stay inert, or empty when it can record. */
    public static Optional<String> reason(List<Hook> hooks) {
        boolean weblogic = System.getProperty("weblogic.home") != null
                || ClassLoader.getSystemResource("weblogic/Server.class") != null;
        if (!weblogic) {
            return Optional.of("not a WebLogic server (no weblogic.home property, no weblogic.Server class)");
        }
        List<String> missing = hooks.stream()
                .flatMap(hook -> hook.missingTargetClasses().stream())
                .collect(Collectors.toList());
        return missing.isEmpty() ? Optional.empty() : Optional.of("WebLogic without the expected container classes " + missing);
    }
}
