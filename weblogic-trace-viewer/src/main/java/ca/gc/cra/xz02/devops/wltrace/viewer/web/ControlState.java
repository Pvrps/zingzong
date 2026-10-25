package ca.gc.cra.xz02.devops.wltrace.viewer.web;

import java.util.Collections;
import java.util.List;

/**
 * The state bar's facts: a kind and a headline sentence, whether a recording can start, what is
 * installed on disk, the servers and the active recording. Produced by a {@link DomainControl},
 * written to the page as JSON by {@link StateHandler}.
 */
public final class ControlState {

    /** One server JVM and what its agent reports. */
    public static final class Server {
        public final String pid;
        public final String serverName;
        public final boolean agentLoaded;
        public final String agentBuild;
        public final boolean agentCurrent;
        public final boolean recording;
        public final String recordingId;
        public final String recordingName;
        public final long traceCount;
        public final long droppedTraces;

        public Server(String pid, String serverName, boolean agentLoaded, String agentBuild, boolean agentCurrent,
                      boolean recording, String recordingId, String recordingName, long traceCount, long droppedTraces) {
            this.pid = pid;
            this.serverName = serverName;
            this.agentLoaded = agentLoaded;
            this.agentBuild = agentBuild;
            this.agentCurrent = agentCurrent;
            this.recording = recording;
            this.recordingId = recordingId;
            this.recordingName = recordingName;
            this.traceCount = traceCount;
            this.droppedTraces = droppedTraces;
        }
    }

    /** A machine-readable name for the headline, such as {@code READY}. */
    public final String kind;
    public final String message;
    public final boolean ready;
    public final boolean enabledOnDisk;
    public final boolean blockPresent;
    public final boolean jarPresent;
    public final boolean jarCurrent;
    /** The domain, as a path or a name the user recognises. */
    public final String domain;
    public final String webLogicVersion;
    public final String pluginAgentBuild;
    public final String activeRecordingId;
    public final String activeRecordingName;
    public final List<Server> servers;

    public ControlState(String kind, String message, boolean ready, boolean enabledOnDisk, boolean blockPresent,
                        boolean jarPresent, boolean jarCurrent, String domain, String webLogicVersion, String pluginAgentBuild,
                        String activeRecordingId, String activeRecordingName, List<Server> servers) {
        this.kind = kind;
        this.message = message;
        this.ready = ready;
        this.enabledOnDisk = enabledOnDisk;
        this.blockPresent = blockPresent;
        this.jarPresent = jarPresent;
        this.jarCurrent = jarCurrent;
        this.domain = domain;
        this.webLogicVersion = webLogicVersion;
        this.pluginAgentBuild = pluginAgentBuild;
        this.activeRecordingId = activeRecordingId;
        this.activeRecordingName = activeRecordingName;
        this.servers = Collections.unmodifiableList(servers);
    }
}
