package io.capture.plugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What a person needs to know about a domain at one moment: enabled on disk or not, which
 * server JVMs of this domain run, which of them have the agent, and whether a recording is
 * active. Every message is one of five sentences.
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

        Server(String pid, String serverName, boolean agentLoaded, AgentControl.Status status) {
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
    }

    public final Kind kind;
    /** The headline sentence, with the specifics after a colon. */
    public final String message;
    public final boolean blockPresent;
    public final boolean jarPresent;
    public final boolean jarCurrent;
    public final List<Server> servers;
    /** The id every recording server reports, or null when none records. */
    public final String activeRecordingId;
    public final String activeRecordingName;

    DomainState(boolean blockPresent, boolean jarPresent, boolean jarCurrent, List<Server> servers, Domain domain) {
        this.blockPresent = blockPresent;
        this.jarPresent = jarPresent;
        this.jarCurrent = jarCurrent;
        this.servers = Collections.unmodifiableList(new ArrayList<Server>(servers));
        String id = null;
        String name = null;
        for (Server s : servers) {
            if (s.recording && id == null) {
                id = s.recordingId;
                name = s.recordingName;
            }
        }
        this.activeRecordingId = id;
        this.activeRecordingName = name;

        boolean enabledOnDisk = blockPresent && jarPresent && jarCurrent;
        List<String> withAgent = new ArrayList<String>();
        List<String> withoutAgent = new ArrayList<String>();
        for (Server s : servers) {
            (s.agentLoaded ? withAgent : withoutAgent).add(s.serverName + " (pid " + s.pid + ")");
        }
        if (!enabledOnDisk) {
            if (!withAgent.isEmpty()) {
                kind = Kind.DISABLED_AGENT_LOADED;
                message = kind.message + ": " + join(withAgent) + "; the agent is idle, no advice is installed";
            } else {
                kind = Kind.NOT_ENABLED;
                String why = !blockPresent && !jarPresent ? "run capture:enable -Dcapture.domain=" + domain
                        : !blockPresent ? "the agent jar is installed but bin/setUserOverrides.sh has no ejb-capture block; run capture:enable"
                        : !jarPresent ? "bin/setUserOverrides.sh has the block but " + domain.installedAgentJar() + " is missing; run capture:enable"
                        : "the installed agent jar is not the one this plugin ships; run capture:enable";
                message = kind.message + ": " + why;
            }
        } else if (servers.isEmpty()) {
            kind = Kind.ENABLED_SERVER_DOWN;
            message = kind.message + ": no JVM of " + domain + " is running for this user";
        } else if (!withoutAgent.isEmpty()) {
            kind = Kind.ENABLED_SERVER_NO_AGENT;
            message = kind.message + ": " + join(withoutAgent) + "; nothing is captured until then";
        } else {
            kind = Kind.READY;
            message = kind.message + ": " + join(withAgent)
                    + (id == null ? "" : "; recording " + id + " (" + name + ") active");
        }
    }

    public boolean enabledOnDisk() {
        return blockPresent && jarPresent && jarCurrent;
    }

    public boolean ready() {
        return kind == Kind.READY;
    }

    public boolean anyAgentLoaded() {
        for (Server s : servers) {
            if (s.agentLoaded) {
                return true;
            }
        }
        return false;
    }

    private static String join(List<String> items) {
        StringBuilder sb = new StringBuilder();
        for (String item : items) {
            sb.append(sb.length() == 0 ? "" : ", ").append(item);
        }
        return sb.toString();
    }
}
