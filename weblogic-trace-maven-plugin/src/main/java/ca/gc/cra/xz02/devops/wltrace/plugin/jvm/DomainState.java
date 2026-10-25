package ca.gc.cra.xz02.devops.wltrace.plugin.jvm;

import ca.gc.cra.xz02.devops.wltrace.plugin.domain.Domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * What a person needs to know about a domain at one moment: enabled on disk or not, which server
 * JVMs of this domain run, which of them have the agent, and whether a recording is active. The
 * message is one of five sentences, with the specifics after a colon.
 */
public final class DomainState {

    public enum Kind {
        NOT_ENABLED("not enabled"),
        ENABLED_SERVER_DOWN("enabled, server not running"),
        ENABLED_SERVER_NO_AGENT("enabled, server running without the agent, restart it"),
        DISABLED_AGENT_LOADED("disabled on disk, agent still loaded until restart"),
        READY("ready");

        public final String message;

        Kind(String message) {
            this.message = message;
        }
    }

    /** One server JVM of the domain. */
    public static final class Server {
        public final String pid;
        public final String serverName;
        public final boolean agentLoaded;
        /** The loaded agent's build; null without the agent. */
        public final String agentBuild;
        public final boolean recording;
        public final String recordingId;
        public final String recordingName;
        public final long traceCount;
        public final long droppedTraces;

        public Server(String pid, String serverName, boolean agentLoaded, AgentControl.Status status) {
            this.pid = pid;
            this.serverName = serverName;
            this.agentLoaded = agentLoaded;
            this.agentBuild = status == null ? null : status.agentBuild;
            this.recording = status != null && status.recording;
            this.recordingId = status == null ? null : status.recordingId;
            this.recordingName = status == null ? null : status.recordingName;
            this.traceCount = status == null ? 0 : status.traceCount;
            this.droppedTraces = status == null ? 0 : status.droppedTraces;
        }

        String label() {
            return serverName + " (pid " + pid + ")";
        }
    }

    public final Kind kind;
    public final String message;
    public final boolean blockPresent;
    public final boolean jarPresent;
    public final boolean jarCurrent;
    public final List<Server> servers;
    /** The id the recording servers report, or null when none records. */
    public final String activeRecordingId;
    public final String activeRecordingName;

    public DomainState(boolean blockPresent, boolean jarPresent, boolean jarCurrent, List<Server> servers, Domain domain) {
        this.blockPresent = blockPresent;
        this.jarPresent = jarPresent;
        this.jarCurrent = jarCurrent;
        this.servers = Collections.unmodifiableList(new ArrayList<>(servers));
        Server recordingServer = servers.stream().filter(server -> server.recording).findFirst().orElse(null);
        this.activeRecordingId = recordingServer == null ? null : recordingServer.recordingId;
        this.activeRecordingName = recordingServer == null ? null : recordingServer.recordingName;

        String withAgent = servers.stream().filter(server -> server.agentLoaded).map(Server::label).collect(Collectors.joining(", "));
        String withoutAgent = servers.stream().filter(server -> !server.agentLoaded).map(Server::label).collect(Collectors.joining(", "));
        if (!enabledOnDisk()) {
            if (blockPresent && jarPresent) {
                // Enabled, but with an agent jar this plugin did not ship: a rebuilt plugin, or a mix of versions.
                kind = Kind.NOT_ENABLED;
                message = kind.message + ": the installed agent jar is not the one this plugin ships; run wltrace:enable"
                        + (withAgent.isEmpty() ? "" : ", then restart " + withAgent + " (running an older agent)");
            } else if (!withAgent.isEmpty()) {
                kind = Kind.DISABLED_AGENT_LOADED;
                message = kind.message + ": " + withAgent + "; the agent is idle, no advice is installed";
            } else {
                kind = Kind.NOT_ENABLED;
                message = kind.message + ": " + notEnabledReason(domain);
            }
        } else if (servers.isEmpty()) {
            kind = Kind.ENABLED_SERVER_DOWN;
            message = kind.message + ": no JVM of " + domain + " is running for this user";
        } else if (!withoutAgent.isEmpty()) {
            kind = Kind.ENABLED_SERVER_NO_AGENT;
            message = kind.message + ": " + withoutAgent + "; nothing is recorded until then";
        } else {
            kind = Kind.READY;
            message = kind.message + ": " + withAgent
                    + (activeRecordingId == null ? "" : "; recording " + activeRecordingId + " (" + activeRecordingName + ") active");
        }
    }

    private String notEnabledReason(Domain domain) {
        String overrides = domain.overridesFile().getFileName().toString();
        if (!blockPresent && !jarPresent) {
            return "run wltrace:enable -Dwltrace.domain=" + domain;
        }
        if (!blockPresent) {
            return "the agent jar is installed but bin/" + overrides + " has no weblogic-trace block; run wltrace:enable";
        }
        return "bin/" + overrides + " has the block but " + domain.installedAgentJar() + " is missing; run wltrace:enable";
    }

    public boolean enabledOnDisk() {
        return blockPresent && jarPresent && jarCurrent;
    }

    public boolean ready() {
        return kind == Kind.READY;
    }

    public boolean anyAgentLoaded() {
        return servers.stream().anyMatch(server -> server.agentLoaded);
    }
}
