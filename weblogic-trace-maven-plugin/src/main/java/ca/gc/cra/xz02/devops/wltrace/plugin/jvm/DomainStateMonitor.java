package ca.gc.cra.xz02.devops.wltrace.plugin.jvm;

import ca.gc.cra.xz02.devops.wltrace.plugin.domain.Domain;
import ca.gc.cra.xz02.devops.wltrace.plugin.domain.DomainJdk;
import ca.gc.cra.xz02.devops.wltrace.plugin.domain.OverridesBlock;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;

/**
 * Evaluates the {@link DomainState} on demand and drives recordings on every server JVM of the
 * domain. A server JVM of this domain is a local JVM of the same OS user whose main class is
 * {@code weblogic.Server}, whose {@code weblogic.RootDirectory} (else {@code user.dir}) is the
 * domain home, and which carries a {@code weblogic.Name}. Connections are kept once made,
 * re-made when they break, and forgotten when the JVM disappears.
 */
public final class DomainStateMonitor {

    static final String BUILD_PROPERTIES = "ca/gc/cra/xz02/devops/wltrace/agent/bootstrap/build.properties";
    private static final long RECONNECT_INTERVAL_MILLIS = 2000;

    /** A server JVM of this domain that has been seen. */
    private static final class KnownServer {
        final String pid;
        final String serverName;
        final int javaMajor;
        AgentControl control;
        boolean agentPresent;
        long lastConnectAttempt;

        KnownServer(String pid, String serverName, int javaMajor) {
            this.pid = pid;
            this.serverName = serverName;
            this.javaMajor = javaMajor;
        }
    }

    private final Domain domain;
    private final Path pluginAgentJar;
    private final Path outputDirectory;
    private final String pluginAgentBuild;
    private final LocalJvms jvms;
    private final Map<String, KnownServer> known = new LinkedHashMap<>();
    private final Set<String> notOurs = new HashSet<>();

    public DomainStateMonitor(Domain domain, Path pluginAgentJar, Path outputDirectory, LocalJvms jvms) {
        this.domain = domain;
        this.pluginAgentJar = pluginAgentJar;
        this.outputDirectory = outputDirectory;
        this.pluginAgentBuild = buildStamp(pluginAgentJar).orElse(null);
        this.jvms = jvms;
    }

    public Domain domain() {
        return domain;
    }

    public Path outputDirectory() {
        return outputDirectory;
    }

    public Path pluginAgentJar() {
        return pluginAgentJar;
    }

    /** The build stamp of the agent jar this plugin ships, or null when it has none. */
    public String pluginAgentBuild() {
        return pluginAgentBuild;
    }

    public synchronized DomainState evaluate() {
        boolean blockPresent;
        try {
            blockPresent = OverridesBlock.isPresent(domain.overridesFile());
        } catch (IOException failure) {
            blockPresent = false;
        }
        Path installed = domain.installedAgentJar();
        boolean jarPresent = Files.isRegularFile(installed);
        boolean jarCurrent = jarPresent && pluginAgentBuild != null && pluginAgentBuild.equals(buildStamp(installed).orElse(null));
        refreshJvms();
        List<DomainState.Server> servers = new ArrayList<>();
        for (KnownServer server : known.values()) {
            AgentControl.Status status = null;
            if (server.agentPresent && server.control != null) {
                try {
                    status = server.control.status();
                } catch (Exception unreachable) {
                    status = null;
                }
            }
            servers.add(new DomainState.Server(server.pid, server.serverName, server.agentPresent, status));
        }
        return new DomainState(blockPresent, jarPresent, jarCurrent, servers, domain);
    }

    /**
     * Starts one recording, under one new id, on every server JVM that has the agent.
     *
     * @throws IllegalStateException when the state is not ready, a recording is active, or a server refuses
     */
    public synchronized String startRecording(String name) {
        DomainState state = evaluate();
        if (!state.ready()) {
            throw new IllegalStateException(state.message);
        }
        if (state.activeRecordingId != null) {
            throw new IllegalStateException("recording " + state.activeRecordingId + " is active; stop it first");
        }
        String id = newRecordingId();
        List<String> failures = new ArrayList<>();
        for (KnownServer server : known.values()) {
            if (server.agentPresent && server.control != null) {
                try {
                    server.control.start(id, name);
                } catch (Exception failure) {
                    failures.add(server.serverName + ": " + failure);
                }
            }
        }
        if (!failures.isEmpty()) {
            throw new IllegalStateException("could not start on " + failures);
        }
        return id;
    }

    /** Stops the active recording on every server JVM; returns the ids reported, normally one. */
    public synchronized List<String> stopRecording() {
        List<String> stopped = new ArrayList<>();
        for (KnownServer server : known.values()) {
            if (server.agentPresent && server.control != null) {
                try {
                    String id = server.control.stop();
                    if (id != null && !stopped.contains(id)) {
                        stopped.add(id);
                    }
                } catch (Exception ignored) {
                    // the server may have gone; evaluate() notices
                }
            }
        }
        return stopped;
    }

    /** The Java major version of a running server JVM of this domain, or 0 when none runs. */
    public synchronized int runningServerJavaMajor() {
        refreshJvms();
        return known.values().stream().mapToInt(server -> server.javaMajor).filter(major -> major > 0).findFirst().orElse(0);
    }

    public synchronized void close() {
        known.values().stream().filter(server -> server.control != null).forEach(server -> server.control.close());
        known.clear();
    }

    private void refreshJvms() {
        List<LocalJvms.Candidate> candidates;
        try {
            candidates = jvms.list();
        } catch (Exception failure) {
            return;
        }
        Set<String> alive = new HashSet<>();
        String own = LocalJvms.ownPid();
        for (LocalJvms.Candidate candidate : candidates) {
            alive.add(candidate.pid);
            if (candidate.pid.equals(own) || known.containsKey(candidate.pid) || notOurs.contains(candidate.pid)) {
                continue;
            }
            if (!looksLikeWebLogic(candidate.displayName)) {
                notOurs.add(candidate.pid);
                continue;
            }
            Optional<Properties> properties = jvms.systemProperties(candidate.pid);
            if (!properties.isPresent()) {
                continue; // not attachable yet (still starting) or not ours to attach: try again next time
            }
            if (!belongsToDomain(properties.get())) {
                notOurs.add(candidate.pid);
                continue;
            }
            KnownServer server = new KnownServer(candidate.pid, properties.get().getProperty("weblogic.Name", "server"),
                    DomainJdk.majorOf(properties.get().getProperty("java.specification.version", "")));
            connect(server);
            known.put(candidate.pid, server);
        }
        for (String pid : new ArrayList<>(known.keySet())) {
            KnownServer server = known.get(pid);
            if (!alive.contains(pid)) {
                if (server.control != null) {
                    server.control.close();
                }
                known.remove(pid);
            } else if (server.control == null) {
                if (System.currentTimeMillis() - server.lastConnectAttempt > RECONNECT_INTERVAL_MILLIS) {
                    connect(server); // the JVM was still starting when first seen
                }
            } else if (!reachable(server)) {
                server.control.close();
                connect(server);
            }
        }
        notOurs.retainAll(alive);
    }

    private static boolean looksLikeWebLogic(String displayName) {
        return displayName.startsWith("weblogic.Server") || displayName.contains(" weblogic.Server");
    }

    private boolean belongsToDomain(Properties properties) {
        if (properties.getProperty("weblogic.Name") == null) {
            return false;
        }
        String root = properties.getProperty("weblogic.RootDirectory", properties.getProperty("user.dir"));
        return root != null && domain.isHome(Paths.get(root));
    }

    private void connect(KnownServer server) {
        server.control = null;
        server.agentPresent = false;
        server.lastConnectAttempt = System.currentTimeMillis();
        Optional<String> address = jvms.localConnectorAddress(server.pid);
        if (!address.isPresent()) {
            return;
        }
        try {
            AgentControl control = AgentControl.connect(address.get());
            server.control = control;
            server.agentPresent = control.agentPresent();
        } catch (Exception failure) {
            server.control = null;
        }
    }

    private static boolean reachable(KnownServer server) {
        try {
            return server.control.agentPresent();
        } catch (Exception failure) {
            return false;
        }
    }

    /** {@code rec-yyyyMMdd-HHmmss}, unique among the directories under the output directory. */
    private String newRecordingId() {
        String base = "rec-" + new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
        String id = base;
        for (int suffix = 2; Files.exists(outputDirectory.resolve(id)); suffix++) {
            id = base + "-" + suffix;
        }
        return id;
    }

    /** The {@code build=} stamp of an agent jar's build.properties. */
    public static Optional<String> buildStamp(Path jar) {
        if (jar == null || !Files.isRegularFile(jar)) {
            return Optional.empty();
        }
        try (JarFile jarFile = new JarFile(jar.toFile())) {
            ZipEntry entry = jarFile.getEntry(BUILD_PROPERTIES);
            if (entry == null) {
                return Optional.empty();
            }
            try (InputStream in = jarFile.getInputStream(entry)) {
                Properties properties = new Properties();
                properties.load(in);
                return Optional.ofNullable(properties.getProperty("build"));
            }
        } catch (IOException failure) {
            return Optional.empty();
        }
    }
}
