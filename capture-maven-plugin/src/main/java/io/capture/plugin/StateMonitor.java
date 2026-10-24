package io.capture.plugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;

/**
 * Evaluates {@link DomainState} on demand and drives captures. Keeps a connection per server
 * JVM once found (attaching is not free), re-attaches when one breaks, forgets JVMs that
 * disappear, and never attaches to a JVM that does not look like a WebLogic server.
 * A server JVM of this domain is a local JVM, same OS user, whose {@code weblogic.RootDirectory}
 * (or, absent that, {@code user.dir}) is the domain home and that carries a {@code weblogic.Name}.
 */
public final class StateMonitor {

    static final String BUILD_PROPERTIES = "io/capture/agent/build.properties";

    private final Domain domain;
    private final File pluginAgentJar;
    private final File outDir;
    private final String pluginAgentBuild;
    private final Map<String, Known> known = new LinkedHashMap<String, Known>();
    private final Set<String> notOurs = new HashSet<String>();

    /** A server JVM of this domain we have seen. */
    private static final class Known {
        final String pid;
        final String serverName;
        final int javaMajor;
        AgentControl control;
        boolean agentPresent;
        long lastConnectAttempt;

        Known(String pid, String serverName, int javaMajor) {
            this.pid = pid;
            this.serverName = serverName;
            this.javaMajor = javaMajor;
        }
    }

    public StateMonitor(Domain domain, File pluginAgentJar, File outDir) {
        this.domain = domain;
        this.pluginAgentJar = pluginAgentJar;
        this.outDir = outDir;
        this.pluginAgentBuild = buildStamp(pluginAgentJar);
    }

    public Domain domain() {
        return domain;
    }

    public File outDir() {
        return outDir;
    }

    public synchronized DomainState evaluate() {
        boolean blockPresent;
        try {
            blockPresent = OverridesBlock.isPresent(domain.setUserOverrides());
        } catch (IOException e) {
            blockPresent = false;
        }
        File installed = domain.installedAgentJar();
        boolean jarPresent = installed.isFile();
        boolean jarCurrent = jarPresent && pluginAgentBuild != null && pluginAgentBuild.equals(buildStamp(installed));
        refreshJvms();
        List<DomainState.Server> servers = new ArrayList<DomainState.Server>();
        for (Known k : known.values()) {
            AgentControl.Status status = null;
            if (k.agentPresent && k.control != null) {
                try {
                    status = k.control.status();
                } catch (Exception e) {
                    status = null;
                }
            }
            servers.add(new DomainState.Server(k.pid, k.serverName, k.agentPresent, status));
        }
        return new DomainState(blockPresent, jarPresent, jarCurrent, servers, domain);
    }

    /** Starts one recording, under one new id, on every server JVM that has the agent. */
    public synchronized String startCapture(String name) throws Exception {
        DomainState state = evaluate();
        if (!state.ready()) {
            throw new IllegalStateException(state.message);
        }
        if (state.activeRecordingId != null) {
            throw new IllegalStateException("recording " + state.activeRecordingId + " is active; stop it first");
        }
        String id = newRecordingId();
        List<String> failures = new ArrayList<String>();
        for (Known k : known.values()) {
            if (k.agentPresent && k.control != null) {
                try {
                    k.control.start(id, name);
                } catch (Exception e) {
                    failures.add(k.serverName + ": " + e);
                }
            }
        }
        if (!failures.isEmpty()) {
            throw new IllegalStateException("could not start on " + failures);
        }
        return id;
    }

    /** Stops the active recording on every server JVM; returns the ids reported (normally one). */
    public synchronized List<String> stopCapture() {
        List<String> stopped = new ArrayList<String>();
        for (Known k : known.values()) {
            if (k.agentPresent && k.control != null) {
                try {
                    String id = k.control.stop();
                    if (id != null && !stopped.contains(id)) {
                        stopped.add(id);
                    }
                } catch (Exception ignored) {
                    // the server may have gone; evaluate() will notice
                }
            }
        }
        return stopped;
    }

    /** The Java major version of a running server JVM of this domain, or 0 when none runs. */
    public synchronized int runningServerJavaMajor() {
        refreshJvms();
        for (Known k : known.values()) {
            if (k.javaMajor > 0) {
                return k.javaMajor;
            }
        }
        return 0;
    }

    public synchronized void close() {
        for (Known k : known.values()) {
            if (k.control != null) {
                k.control.close();
            }
        }
        known.clear();
    }

    // ---- JVM discovery -----------------------------------------------------------------------

    private void refreshJvms() {
        List<LocalJvms.Candidate> candidates;
        try {
            candidates = LocalJvms.list();
        } catch (Exception e) {
            return;
        }
        Set<String> alive = new HashSet<String>();
        String own = LocalJvms.ownPid();
        for (LocalJvms.Candidate c : candidates) {
            alive.add(c.pid);
            if (c.pid.equals(own) || known.containsKey(c.pid) || notOurs.contains(c.pid)) {
                continue;
            }
            if (!looksLikeWebLogic(c.displayName)) {
                notOurs.add(c.pid);
                continue;
            }
            Properties props = LocalJvms.systemProperties(c.pid);
            if (props == null) {
                continue; // not attachable yet (still starting) or not ours to attach: try again next time
            }
            if (!belongsToDomain(props)) {
                notOurs.add(c.pid);
                continue;
            }
            Known k = new Known(c.pid, props.getProperty("weblogic.Name", "server"),
                    Domain.majorOf(props.getProperty("java.specification.version", "")));
            connect(k);
            known.put(c.pid, k);
        }
        for (String pid : new ArrayList<String>(known.keySet())) {
            Known k = known.get(pid);
            if (!alive.contains(pid)) {
                if (k.control != null) {
                    k.control.close();
                }
                known.remove(pid);
            } else if (k.control == null) {
                if (System.currentTimeMillis() - k.lastConnectAttempt > 2000) {
                    connect(k); // the JVM was still starting when first seen
                }
            } else if (!reachable(k)) {
                k.control.close();
                connect(k);
            }
        }
        notOurs.retainAll(alive);
    }

    private static boolean looksLikeWebLogic(String displayName) {
        return displayName.startsWith("weblogic.Server") || displayName.contains(" weblogic.Server");
    }

    private boolean belongsToDomain(Properties props) {
        if (props.getProperty("weblogic.Name") == null) {
            return false;
        }
        String root = props.getProperty("weblogic.RootDirectory");
        if (root == null) {
            root = props.getProperty("user.dir");
        }
        return root != null && Domain.canonical(new File(root)).equals(domain.home);
    }

    private void connect(Known k) {
        k.control = null;
        k.agentPresent = false;
        k.lastConnectAttempt = System.currentTimeMillis();
        String address = LocalJvms.localConnectorAddress(k.pid);
        if (address == null) {
            return;
        }
        try {
            AgentControl control = AgentControl.connect(address);
            k.control = control;
            k.agentPresent = control.agentPresent();
        } catch (Exception e) {
            k.control = null;
        }
    }

    private static boolean reachable(Known k) {
        try {
            return k.control.agentPresent();
        } catch (Exception e) {
            return false;
        }
    }

    // ---- recording ids and build stamps ---------------------------------------------------------

    /** rec-yyyyMMdd-HHmmss, unique among the directories under the output directory. */
    private String newRecordingId() {
        String base = "rec-" + new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
        String id = base;
        for (int n = 2; new File(outDir, id).exists(); n++) {
            id = base + "-" + n;
        }
        return id;
    }

    /** The {@code build=} stamp of an agent jar's build.properties, or null. */
    public static String buildStamp(File jar) {
        if (jar == null || !jar.isFile()) {
            return null;
        }
        try {
            JarFile jf = new JarFile(jar);
            try {
                ZipEntry entry = jf.getEntry(BUILD_PROPERTIES);
                if (entry == null) {
                    return null;
                }
                InputStream in = jf.getInputStream(entry);
                try {
                    Properties p = new Properties();
                    p.load(in);
                    return p.getProperty("build");
                } finally {
                    in.close();
                }
            } finally {
                jf.close();
            }
        } catch (IOException e) {
            return null;
        }
    }

    public String pluginAgentBuild() {
        return pluginAgentBuild;
    }

    public File pluginAgentJar() {
        return pluginAgentJar;
    }
}
