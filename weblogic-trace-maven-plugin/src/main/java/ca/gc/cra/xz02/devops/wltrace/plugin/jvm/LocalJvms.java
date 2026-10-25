package ca.gc.cra.xz02.devops.wltrace.plugin.jvm;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

/**
 * The JDK's attach API, reached reflectively so the plugin compiles for Java 8 and runs on any
 * JDK: on 9 and later {@code com.sun.tools.attach} comes with the JDK; on 8 it lives in
 * {@code lib/tools.jar}, loaded from the running JDK's home. Lists the local JVMs of the same OS
 * user, reads their system properties, and obtains their local JMX connector address.
 */
public final class LocalJvms {

    private static final String VIRTUAL_MACHINE = "com.sun.tools.attach.VirtualMachine";
    private static final String LOCAL_CONNECTOR_ADDRESS = "com.sun.management.jmxremote.localConnectorAddress";

    /** A JVM as the attach API lists it. */
    public static final class Candidate {
        public final String pid;
        public final String displayName;

        Candidate(String pid, String displayName) {
            this.pid = pid;
            this.displayName = displayName;
        }
    }

    private volatile Class<?> virtualMachineClass;

    public static String ownPid() {
        String name = ManagementFactory.getRuntimeMXBean().getName();
        int at = name.indexOf('@');
        return at < 0 ? name : name.substring(0, at);
    }

    public List<Candidate> list() throws Exception {
        Class<?> virtualMachine = virtualMachineClass();
        List<?> descriptors = (List<?>) virtualMachine.getMethod("list").invoke(null);
        List<Candidate> result = new ArrayList<>();
        for (Object descriptor : descriptors) {
            String id = (String) descriptor.getClass().getMethod("id").invoke(descriptor);
            String displayName = (String) descriptor.getClass().getMethod("displayName").invoke(descriptor);
            result.add(new Candidate(id, displayName == null ? "" : displayName));
        }
        return result;
    }

    /** The JVM's system properties, or empty when it cannot be attached (gone, another user's). */
    public Optional<Properties> systemProperties(String pid) {
        try {
            Object virtualMachine = attach(pid);
            try {
                return Optional.of((Properties) virtualMachineClass().getMethod("getSystemProperties").invoke(virtualMachine));
            } finally {
                detach(virtualMachine);
            }
        } catch (Throwable failure) {
            return Optional.empty();
        }
    }

    /** The JVM's local JMX connector address, starting its local management agent when needed; empty on failure. */
    public Optional<String> localConnectorAddress(String pid) {
        try {
            Object virtualMachine = attach(pid);
            try {
                Properties agentProperties = (Properties) virtualMachineClass().getMethod("getAgentProperties").invoke(virtualMachine);
                String address = agentProperties.getProperty(LOCAL_CONNECTOR_ADDRESS);
                if (address == null) {
                    virtualMachineClass().getMethod("startLocalManagementAgent").invoke(virtualMachine);
                    agentProperties = (Properties) virtualMachineClass().getMethod("getAgentProperties").invoke(virtualMachine);
                    address = agentProperties.getProperty(LOCAL_CONNECTOR_ADDRESS);
                }
                return Optional.ofNullable(address);
            } finally {
                detach(virtualMachine);
            }
        } catch (Throwable failure) {
            return Optional.empty();
        }
    }

    private Object attach(String pid) throws Exception {
        return virtualMachineClass().getMethod("attach", String.class).invoke(null, pid);
    }

    private void detach(Object virtualMachine) {
        try {
            virtualMachineClass().getMethod("detach").invoke(virtualMachine);
        } catch (Throwable ignored) {
            // best effort
        }
    }

    private Class<?> virtualMachineClass() throws Exception {
        Class<?> loaded = virtualMachineClass;
        if (loaded != null) {
            return loaded;
        }
        try {
            loaded = Class.forName(VIRTUAL_MACHINE);
        } catch (ClassNotFoundException notOnClassPath) {
            try {
                loaded = ClassLoader.getSystemClassLoader().loadClass(VIRTUAL_MACHINE);
            } catch (ClassNotFoundException notInSystemLoader) {
                File toolsJar = toolsJar().orElseThrow(() -> new IllegalStateException(
                        "the attach API is not available: not a JDK? (" + System.getProperty("java.home") + ")"));
                @SuppressWarnings("resource")
                URLClassLoader toolsLoader = new URLClassLoader(new URL[]{toolsJar.toURI().toURL()}, ClassLoader.getSystemClassLoader());
                loaded = toolsLoader.loadClass(VIRTUAL_MACHINE);
            }
        }
        virtualMachineClass = loaded;
        return loaded;
    }

    /** JDK 8: {@code lib/tools.jar} under the JDK home, whether {@code java.home} is the JDK or its jre. */
    private static Optional<File> toolsJar() {
        File home = new File(System.getProperty("java.home"));
        File[] candidates = {new File(home, "lib/tools.jar"), new File(home.getParentFile(), "lib/tools.jar")};
        for (File candidate : candidates) {
            if (candidate.isFile()) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }
}
