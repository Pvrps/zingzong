package io.capture.plugin;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * The JDK's attach API, reached reflectively so the plugin compiles for Java 8 and runs on
 * any JDK: on 9+ {@code com.sun.tools.attach} comes with the JDK; on 8 it lives in
 * {@code lib/tools.jar}, loaded from the running JDK's home. Lists local JVMs of the same OS
 * user, reads their system properties, and obtains the local JMX connector address.
 */
public final class LocalJvms {

    private static final String VM_CLASS = "com.sun.tools.attach.VirtualMachine";
    private static final String LOCAL_CONNECTOR = "com.sun.management.jmxremote.localConnectorAddress";

    /** A JVM as the attach API lists it. */
    public static final class Candidate {
        public final String pid;
        public final String displayName;

        Candidate(String pid, String displayName) {
            this.pid = pid;
            this.displayName = displayName;
        }
    }

    private static volatile Class<?> vmClass;

    private LocalJvms() {
    }

    public static String ownPid() {
        String name = ManagementFactory.getRuntimeMXBean().getName();
        int at = name.indexOf('@');
        return at < 0 ? name : name.substring(0, at);
    }

    public static List<Candidate> list() throws Exception {
        Class<?> vm = vmClass();
        List<?> descriptors = (List<?>) vm.getMethod("list").invoke(null);
        List<Candidate> result = new ArrayList<Candidate>();
        for (Object d : descriptors) {
            String id = (String) d.getClass().getMethod("id").invoke(d);
            String display = (String) d.getClass().getMethod("displayName").invoke(d);
            result.add(new Candidate(id, display == null ? "" : display));
        }
        return result;
    }

    /** The JVM's system properties, or null when it cannot be attached (gone, other user). */
    public static Properties systemProperties(String pid) {
        try {
            Object vm = attach(pid);
            try {
                return (Properties) vmClass().getMethod("getSystemProperties").invoke(vm);
            } finally {
                detach(vm);
            }
        } catch (Throwable t) {
            return null;
        }
    }

    /** The JVM's local JMX connector address, starting the local management agent when needed; null on failure. */
    public static String localConnectorAddress(String pid) {
        try {
            Object vm = attach(pid);
            try {
                Properties agent = (Properties) vmClass().getMethod("getAgentProperties").invoke(vm);
                String address = agent.getProperty(LOCAL_CONNECTOR);
                if (address == null) {
                    vmClass().getMethod("startLocalManagementAgent").invoke(vm);
                    agent = (Properties) vmClass().getMethod("getAgentProperties").invoke(vm);
                    address = agent.getProperty(LOCAL_CONNECTOR);
                }
                return address;
            } finally {
                detach(vm);
            }
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object attach(String pid) throws Exception {
        return vmClass().getMethod("attach", String.class).invoke(null, pid);
    }

    private static void detach(Object vm) {
        try {
            vmClass().getMethod("detach").invoke(vm);
        } catch (Throwable ignored) {
            // best effort
        }
    }

    private static Class<?> vmClass() throws Exception {
        Class<?> c = vmClass;
        if (c != null) {
            return c;
        }
        try {
            c = Class.forName(VM_CLASS);
        } catch (ClassNotFoundException outer) {
            try {
                c = ClassLoader.getSystemClassLoader().loadClass(VM_CLASS);
            } catch (ClassNotFoundException system) {
                File toolsJar = toolsJar();
                if (toolsJar == null) {
                    throw new IllegalStateException("the attach API is not available: not a JDK? (" + System.getProperty("java.home") + ")");
                }
                @SuppressWarnings("resource")
                URLClassLoader loader = new URLClassLoader(new URL[]{toolsJar.toURI().toURL()}, ClassLoader.getSystemClassLoader());
                c = loader.loadClass(VM_CLASS);
            }
        }
        vmClass = c;
        return c;
    }

    /** JDK 8: {@code $JAVA_HOME/lib/tools.jar}, whether java.home is the JDK or its jre. */
    private static File toolsJar() {
        File home = new File(System.getProperty("java.home"));
        File[] candidates = {new File(home, "lib/tools.jar"), new File(home.getParentFile(), "lib/tools.jar")};
        for (File f : candidates) {
            if (f.isFile()) {
                return f;
            }
        }
        return null;
    }
}
